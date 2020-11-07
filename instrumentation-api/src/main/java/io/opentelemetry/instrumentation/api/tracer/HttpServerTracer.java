/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.api.tracer;

import static io.opentelemetry.api.OpenTelemetry.getGlobalPropagators;
import static io.opentelemetry.api.trace.Span.Kind.SERVER;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.attributes.SemanticAttributes;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.instrumentation.api.aiappid.AiAppId;
import io.opentelemetry.instrumentation.api.context.ContextPropagationDebug;
import io.opentelemetry.instrumentation.api.decorator.HttpStatusConverter;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// TODO In search for a better home package
public abstract class HttpServerTracer<REQUEST, RESPONSE, CONNECTION, STORAGE> extends BaseTracer {

  private static final Logger log = LoggerFactory.getLogger(HttpServerTracer.class);

  public static final String CONTEXT_ATTRIBUTE = "io.opentelemetry.instrumentation.context";

  protected static final String USER_AGENT = "User-Agent";

  private static final boolean FAIL_ON_CONTEXT_LEAK =
      Boolean.getBoolean("otel.internal.failOnContextLeak");

  protected static final String AI_REQUEST_CONTEXT_HEADER_NAME = "Request-Context";

  private static final boolean AI_BACK_COMPAT = true;
  private static final String AI_REQUEST_CONTEXT_HEADER_APPID_KEY = "appId";

  public HttpServerTracer() {
    super();
  }

  public HttpServerTracer(Tracer tracer) {
    super(tracer);
  }

  public Context startSpan(REQUEST request, CONNECTION connection, Method origin) {
    String spanName = spanNameForMethod(origin);
    return startSpan(request, connection, spanName);
  }

  public Context startSpan(REQUEST request, CONNECTION connection, String spanName) {
    return startSpan(request, connection, spanName, -1);
  }

  public Context startSpan(
      REQUEST request, CONNECTION connection, String spanName, long startTimestamp) {
    Context parentContext = extract(request, getGetter());
    Span.Builder builder =
        tracer.spanBuilder(spanName).setSpanKind(SERVER).setParent(parentContext);

    if (startTimestamp >= 0) {
      builder.setStartTimestamp(startTimestamp);
    }

    Span span = builder.startSpan();
    onConnection(span, connection);
    onRequest(span, request);
    onConnectionAndRequest(span, connection, request);

    return parentContext.with(span);
  }

  /**
   * Creates new scoped context, based on the current context, with the given span.
   *
   * <p>Attaches new context to the request to avoid creating duplicate server spans.
   */
  public Scope startScope(Span span, STORAGE storage) {
    return startScope(span, storage, Context.current());
  }

  /**
   * Creates new scoped context, based on the given context, with the given span.
   *
   * <p>Attaches new context to the request to avoid creating duplicate server spans.
   */
  public Scope startScope(Span span, STORAGE storage, Context context) {
    // TODO we could do this in one go, but TracingContextUtils.CONTEXT_SPAN_KEY is private
    Context newContext = context.with(CONTEXT_SERVER_SPAN_KEY, span).with(span);
    attachServerContext(newContext, storage);
    return newContext.makeCurrent();
  }

  /**
   * Convenience method. Delegates to {@link #end(Span, Object, long)}, passing {@code timestamp}
   * value of {@code -1}.
   */
  // TODO should end methods remove SPAN attribute from request as well?
  public void end(Span span, RESPONSE response) {
    end(span, response, -1);
  }

  // TODO should end methods remove SPAN attribute from request as well?
  public void end(Span span, RESPONSE response, long timestamp) {
    setStatus(span, responseStatus(response));
    endSpan(span, timestamp);
  }

  /**
   * Convenience method. Delegates to {@link #endExceptionally(Span, Throwable, Object)}, passing
   * {@code response} value of {@code null}.
   */
  @Override
  public void endExceptionally(Span span, Throwable throwable) {
    endExceptionally(span, throwable, null);
  }

  /**
   * Convenience method. Delegates to {@link #endExceptionally(Span, Throwable, Object, long)},
   * passing {@code timestamp} value of {@code -1}.
   */
  public void endExceptionally(Span span, Throwable throwable, RESPONSE response) {
    endExceptionally(span, throwable, response, -1);
  }

  /**
   * If {@code response} is {@code null}, the {@code http.status_code} will be set to {@code 500}
   * and the {@link Span} status will be set to {@link io.opentelemetry.api.trace.StatusCode#ERROR}.
   */
  public void endExceptionally(Span span, Throwable throwable, RESPONSE response, long timestamp) {
    onError(span, unwrapThrowable(throwable));
    if (response == null) {
      setStatus(span, 500);
    } else {
      setStatus(span, responseStatus(response));
    }
    endSpan(span, timestamp);
  }

  public Span getServerSpan(STORAGE storage) {
    Context attachedContext = getServerContext(storage);
    return attachedContext == null ? null : attachedContext.get(CONTEXT_SERVER_SPAN_KEY);
  }

  /**
   * Returns context stored to the given request-response-loop storage by {@link
   * #attachServerContext(Context, Object)}.
   */
  @Nullable
  public abstract Context getServerContext(STORAGE storage);

  protected void onConnection(Span span, CONNECTION connection) {
    span.setAttribute(SemanticAttributes.NET_PEER_IP, peerHostIP(connection));
    Integer port = peerPort(connection);
    // Negative or Zero ports might represent an unset/null value for an int type.  Skip setting.
    if (port != null && port > 0) {
      span.setAttribute(SemanticAttributes.NET_PEER_PORT, (long) port);
    }
  }

  protected void onRequest(Span span, REQUEST request) {
    final String sourceAppId = span.getSpanContext().getTraceState().get(AiAppId.TRACESTATE_KEY);
    if (sourceAppId != null && !sourceAppId.isEmpty()) {
      span.setAttribute(AiAppId.SPAN_SOURCE_ATTRIBUTE_NAME, sourceAppId);
    } else if (AI_BACK_COMPAT) {
      final String aiRequestContext = aiRequestContext(request);
      if (aiRequestContext != null) {
        final Map<String, String> map = toMap(aiRequestContext);
        final String backCompatSourceAppId = map.get(AI_REQUEST_CONTEXT_HEADER_APPID_KEY);
        if (backCompatSourceAppId != null && !backCompatSourceAppId.isEmpty()) {
          span.setAttribute(AiAppId.SPAN_SOURCE_ATTRIBUTE_NAME, backCompatSourceAppId);
        }
      }
    }

    span.setAttribute(SemanticAttributes.HTTP_METHOD, method(request));
    span.setAttribute(SemanticAttributes.HTTP_USER_AGENT, requestHeader(request, USER_AGENT));

    setUrl(span, request);

    // TODO set resource name from URL.
  }

  /*
  https://github.com/open-telemetry/opentelemetry-specification/blob/master/specification/trace/semantic_conventions/http.md

  HTTP semantic convention recommends setting http.scheme, http.host, http.target attributes
  instead of http.url because it "is usually not readily available on the server side but would have
  to be assembled in a cumbersome and sometimes lossy process from other information".

  But in Java world there is no standard way to access "The full request target as passed in a HTTP request line or equivalent"
  which is the recommended value for http.target attribute. Therefore we cannot use any of the
  recommended combinations of attributes and are forced to use http.url.
   */
  private void setUrl(Span span, REQUEST request) {
    span.setAttribute(SemanticAttributes.HTTP_URL, url(request));
  }

  protected void onConnectionAndRequest(Span span, CONNECTION connection, REQUEST request) {
    String flavor = flavor(connection, request);
    if (flavor != null) {
      span.setAttribute(SemanticAttributes.HTTP_FLAVOR, flavor);
    }
    span.setAttribute(SemanticAttributes.HTTP_CLIENT_IP, clientIP(connection, request));
  }

  private String clientIP(CONNECTION connection, REQUEST request) {
    // try Forwarded
    String forwarded = requestHeader(request, "Forwarded");
    if (forwarded != null) {
      forwarded = extractForwardedFor(forwarded);
      if (forwarded != null) {
        return forwarded;
      }
    }

    // try X-Forwarded-For
    forwarded = requestHeader(request, "X-Forwarded-For");
    if (forwarded != null) {
      // may be split by ,
      int endIndex = forwarded.indexOf(',');
      if (endIndex > 0) {
        forwarded = forwarded.substring(0, endIndex);
      }
      if (!forwarded.isEmpty()) {
        return forwarded;
      }
    }

    // fallback to peer IP if there are no proxy headers
    return peerHostIP(connection);
  }

  // VisibleForTesting
  static String extractForwardedFor(String forwarded) {
    int start = forwarded.toLowerCase().indexOf("for=");
    if (start < 0) {
      return null;
    }
    start += 4; // start is now the index after for=
    if (start >= forwarded.length() - 1) { // the value after for= must not be empty
      return null;
    }
    for (int i = start; i < forwarded.length() - 1; i++) {
      char c = forwarded.charAt(i);
      if (c == ',' || c == ';') {
        if (i == start) { // empty string
          return null;
        }
        return forwarded.substring(start, i);
      }
    }
    return forwarded.substring(start);
  }

  private <C> Context extract(C carrier, TextMapPropagator.Getter<C> getter) {
    if (ContextPropagationDebug.isThreadPropagationDebuggerEnabled()) {
      debugContextLeak();
    }
    // Using Context.ROOT here may be quite unexpected, but the reason is simple.
    // We want either span context extracted from the carrier or invalid one.
    // We DO NOT want any span context potentially lingering in the current context.
    return getGlobalPropagators().getTextMapPropagator().extract(Context.root(), carrier, getter);
  }

  private void debugContextLeak() {
    Context current = Context.current();
    if (current != Context.root()) {
      log.error("Unexpected non-root current context found when extracting remote context!");
      Span currentSpan = Span.fromContextOrNull(current);
      if (currentSpan != null) {
        log.error("It contains this span: {}", currentSpan);
      }
      List<StackTraceElement[]> locations = ContextPropagationDebug.getLocations(current);
      if (locations != null) {
        StringBuilder sb = new StringBuilder();
        Iterator<StackTraceElement[]> i = locations.iterator();
        while (i.hasNext()) {
          for (StackTraceElement ste : i.next()) {
            sb.append("\n");
            sb.append(ste);
          }
          if (i.hasNext()) {
            sb.append("\nwhich was propagated from:");
          }
        }
        log.error("a context leak was detected. it was propagated from:{}", sb);
      }

      if (FAIL_ON_CONTEXT_LEAK) {
        throw new IllegalStateException("Context leak detected");
      }
    }
  }

  private static void setStatus(Span span, int status) {
    span.setAttribute(SemanticAttributes.HTTP_STATUS_CODE, (long) status);
    // TODO status_message
    // See https://github.com/open-telemetry/opentelemetry-specification/issues/950
    span.setStatus(HttpStatusConverter.statusFromHttpStatus(status));
  }

  private static void endSpan(Span span, long timestamp) {
    if (timestamp >= 0) {
      span.end(timestamp);
    } else {
      span.end();
    }
  }

  @Nullable
  protected abstract Integer peerPort(CONNECTION connection);

  @Nullable
  protected abstract String peerHostIP(CONNECTION connection);

  protected abstract String flavor(CONNECTION connection, REQUEST request);

  protected abstract TextMapPropagator.Getter<REQUEST> getGetter();

  protected abstract String url(REQUEST request);

  protected abstract String method(REQUEST request);

  @Nullable
  protected abstract String requestHeader(REQUEST request, String name);

  protected abstract int responseStatus(RESPONSE response);

  /**
   * Stores given context in the given request-response-loop storage in implementation specific way.
   */
  protected abstract void attachServerContext(Context context, STORAGE storage);

  /*
  We are making quite simple check by just verifying the presence of schema.
   */
  protected boolean isRelativeUrl(String url) {
    return !(url.startsWith("http://") || url.startsWith("https://"));
  }

  protected String aiRequestContext(final REQUEST request) {
    return null;
  }

  private static Map<String, String> toMap(final String str) {
    final Map<String, String> result = new HashMap<>();
    final String[] pairs = str.split(",");
    for (final String pair : pairs) {
      final String[] keyValuePair = pair.trim().split("=");
      if (keyValuePair.length == 2) {
        final String key = keyValuePair[0].trim();
        final String value = keyValuePair[1].trim();
        result.put(key, value);
      }
    }
    return result;
  }
}
