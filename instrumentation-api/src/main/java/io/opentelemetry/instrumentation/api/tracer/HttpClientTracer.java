/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.api.tracer;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Span.Kind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.attributes.SemanticAttributes;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapPropagator.Setter;
import io.opentelemetry.instrumentation.api.aiappid.AiAppId;
import io.opentelemetry.instrumentation.api.decorator.HttpStatusConverter;
import io.opentelemetry.instrumentation.api.tracer.utils.NetPeerUtils;
import java.net.URI;
import java.net.URISyntaxException;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class HttpClientTracer<REQUEST, CARRIER, RESPONSE> extends BaseTracer {

  private static final Logger log = LoggerFactory.getLogger(HttpClientTracer.class);

  public static final String DEFAULT_SPAN_NAME = "HTTP request";

  protected static final String USER_AGENT = "User-Agent";

  protected abstract String method(REQUEST request);

  @Nullable
  protected abstract URI url(REQUEST request) throws URISyntaxException;

  @Nullable
  protected String flavor(REQUEST request) {
    // This is de facto standard nowadays, so let us use it, unless overridden
    return "1.1";
  }

  protected abstract Integer status(RESPONSE response);

  @Nullable
  protected abstract String requestHeader(REQUEST request, String name);

  @Nullable
  protected abstract String responseHeader(RESPONSE response, String name);

  protected abstract TextMapPropagator.Setter<CARRIER> getSetter();

  protected HttpClientTracer() {
    super();
  }

  protected HttpClientTracer(Tracer tracer) {
    super(tracer);
  }

  public Span startSpan(REQUEST request) {
    return startSpan(request, -1);
  }

  public Span startSpan(REQUEST request, long startTimeNanos) {
    return startSpan(request, spanNameForRequest(request), startTimeNanos);
  }

  public Scope startScope(Span span, CARRIER carrier) {
    Context context = Context.current().with(span);

    Setter<CARRIER> setter = getSetter();
    if (setter == null) {
      throw new IllegalStateException(
          "getSetter() not defined but calling startScope(), either getSetter must be implemented or the scope should be setup manually");
    }
    OpenTelemetry.getGlobalPropagators().getTextMapPropagator().inject(context, carrier, setter);
    context = context.with(CONTEXT_CLIENT_SPAN_KEY, span);
    return context.makeCurrent();
  }

  public void end(Span span, RESPONSE response) {
    end(span, response, -1);
  }

  public void end(Span span, RESPONSE response, long endTimeNanos) {
    onResponse(span, response);
    super.end(span, endTimeNanos);
  }

  public void endExceptionally(Span span, RESPONSE response, Throwable throwable) {
    endExceptionally(span, response, throwable, -1);
  }

  public void endExceptionally(
      Span span, RESPONSE response, Throwable throwable, long endTimeNanos) {
    onResponse(span, response);
    super.endExceptionally(span, throwable, endTimeNanos);
  }

  /**
   * Returns a new client {@link Span} if there is no client {@link Span} in the current {@link
   * Context}, or an invalid {@link Span} otherwise.
   */
  private Span startSpan(REQUEST request, String name, long startTimeNanos) {
    Context context = Context.current();
    Span clientSpan = context.get(CONTEXT_CLIENT_SPAN_KEY);

    if (clientSpan != null) {
      // We don't want to create two client spans for a given client call, suppress inner spans.
      return Span.getInvalid();
    }

    Span.Builder spanBuilder = tracer.spanBuilder(name).setSpanKind(Kind.CLIENT).setParent(context);
    if (startTimeNanos > 0) {
      spanBuilder.setStartTimestamp(startTimeNanos);
    }
    Span span = spanBuilder.startSpan();
    onRequest(span, request);
    return span;
  }

  protected Span onRequest(Span span, REQUEST request) {
    assert span != null;
    if (request != null) {
      span.setAttribute(SemanticAttributes.NET_TRANSPORT, "IP.TCP");
      span.setAttribute(SemanticAttributes.HTTP_METHOD, method(request));
      span.setAttribute(SemanticAttributes.HTTP_USER_AGENT, requestHeader(request, USER_AGENT));

      setFlavor(span, request);
      setUrl(span, request);
    }
    return span;
  }

  private void setFlavor(Span span, REQUEST request) {
    String flavor = flavor(request);
    if (flavor == null) {
      return;
    }

    String httpProtocolPrefix = "HTTP/";
    if (flavor.startsWith(httpProtocolPrefix)) {
      flavor = flavor.substring(httpProtocolPrefix.length());
    }

    span.setAttribute(SemanticAttributes.HTTP_FLAVOR, flavor);
  }

  private void setUrl(Span span, REQUEST request) {
    try {
      URI url = url(request);
      if (url != null) {
        NetPeerUtils.setNetPeer(span, url.getHost(), null, url.getPort());
        span.setAttribute(SemanticAttributes.HTTP_URL, url.toString());
      }
    } catch (Exception e) {
      log.debug("Error tagging url", e);
    }
  }

  protected Span onResponse(Span span, RESPONSE response) {
    assert span != null;
    if (response != null) {
      Integer status = status(response);
      if (status != null) {
        span.setAttribute(SemanticAttributes.HTTP_STATUS_CODE, (long) status);
        span.setStatus(HttpStatusConverter.statusFromHttpStatus(status));
      }
      final String responseHeader = responseHeader(response, AiAppId.RESPONSE_HEADER_NAME);
      setTargetAppId(span, responseHeader);
    }
    return span;
  }

  protected String spanNameForRequest(REQUEST request) {
    if (request == null) {
      return DEFAULT_SPAN_NAME;
    }
    String method = method(request);
    return method != null ? "HTTP " + method : DEFAULT_SPAN_NAME;
  }

  private static void setTargetAppId(final Span span, final String responseHeader) {
    if (responseHeader == null) {
      return;
    }
    final int index = responseHeader.indexOf('=');
    if (index == -1) {
      return;
    }
    final String targetAppId = responseHeader.substring(index + 1);
    span.setAttribute(AiAppId.SPAN_TARGET_ATTRIBUTE_NAME, targetAppId);
  }
}
