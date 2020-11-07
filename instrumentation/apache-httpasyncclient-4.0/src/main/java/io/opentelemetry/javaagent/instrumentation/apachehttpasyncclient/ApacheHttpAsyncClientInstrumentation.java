/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.apachehttpasyncclient;

import static io.opentelemetry.instrumentation.api.tracer.HttpClientTracer.DEFAULT_SPAN_NAME;
import static io.opentelemetry.javaagent.instrumentation.apachehttpasyncclient.ApacheHttpAsyncClientTracer.tracer;
import static io.opentelemetry.javaagent.tooling.ClassLoaderMatcher.hasClassesNamed;
import static io.opentelemetry.javaagent.tooling.bytebuddy.matcher.AgentElementMatchers.implementsInterface;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Span.Kind;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.javaagent.instrumentation.api.Java8BytecodeBridge;
import io.opentelemetry.javaagent.tooling.Instrumenter;
import java.io.IOException;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.nio.ContentDecoder;
import org.apache.http.nio.ContentEncoder;
import org.apache.http.nio.IOControl;
import org.apache.http.nio.protocol.HttpAsyncRequestProducer;
import org.apache.http.nio.protocol.HttpAsyncResponseConsumer;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpCoreContext;

@AutoService(Instrumenter.class)
public class ApacheHttpAsyncClientInstrumentation extends Instrumenter.Default {

  public ApacheHttpAsyncClientInstrumentation() {
    super("httpasyncclient", "apache-httpasyncclient");
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    // Optimization for expensive typeMatcher.
    return hasClassesNamed("org.apache.http.nio.client.HttpAsyncClient");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return implementsInterface(named("org.apache.http.nio.client.HttpAsyncClient"));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".HttpHeadersInjectAdapter",
      getClass().getName() + "$DelegatingRequestProducer",
      getClass().getName() + "$DelegatingResponseConsumer",
      getClass().getName() + "$TraceContinuedFutureCallback",
      packageName + ".ApacheHttpAsyncClientTracer"
    };
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        isMethod()
            .and(named("execute"))
            .and(takesArguments(4))
            .and(takesArgument(0, named("org.apache.http.nio.protocol.HttpAsyncRequestProducer")))
            .and(takesArgument(1, named("org.apache.http.nio.protocol.HttpAsyncResponseConsumer")))
            .and(takesArgument(2, named("org.apache.http.protocol.HttpContext")))
            .and(takesArgument(3, named("org.apache.http.concurrent.FutureCallback"))),
        ApacheHttpAsyncClientInstrumentation.class.getName() + "$ClientAdvice");
  }

  public static class ClientAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static Span methodEnter(
        @Advice.Argument(value = 0, readOnly = false) HttpAsyncRequestProducer requestProducer,
        @Advice.Argument(value = 1, readOnly = false) HttpAsyncResponseConsumer responseConsumer,
        @Advice.Argument(2) HttpContext context,
        @Advice.Argument(value = 3, readOnly = false) FutureCallback<?> futureCallback) {

      Context parentContext = Java8BytecodeBridge.currentContext();
      Span clientSpan = tracer().startSpan(DEFAULT_SPAN_NAME, Kind.CLIENT);

      requestProducer = new DelegatingRequestProducer(clientSpan, requestProducer);
      responseConsumer = new DelegatingResponseConsumer(clientSpan, responseConsumer);
      futureCallback =
          new TraceContinuedFutureCallback(parentContext, clientSpan, context, futureCallback);

      return clientSpan;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void methodExit(
        @Advice.Enter Span span, @Advice.Return Object result, @Advice.Thrown Throwable throwable) {
      if (throwable != null) {
        tracer().endExceptionally(span, throwable);
      }
    }
  }

  public static class DelegatingRequestProducer implements HttpAsyncRequestProducer {
    Span span;
    HttpAsyncRequestProducer delegate;

    public DelegatingRequestProducer(Span span, HttpAsyncRequestProducer delegate) {
      this.span = span;
      this.delegate = delegate;
    }

    @Override
    public HttpHost getTarget() {
      return delegate.getTarget();
    }

    @Override
    public HttpRequest generateRequest() throws IOException, HttpException {
      HttpRequest request = delegate.generateRequest();
      span.updateName(tracer().spanNameForRequest(request));
      tracer().onRequest(span, request);

      // TODO (trask) expose inject separate from startScope, e.g. for async cases
      Scope scope = tracer().startScope(span, request);
      scope.close();

      return request;
    }

    @Override
    public void produceContent(ContentEncoder encoder, IOControl ioctrl) throws IOException {
      delegate.produceContent(encoder, ioctrl);
    }

    @Override
    public void requestCompleted(HttpContext context) {
      delegate.requestCompleted(context);
    }

    @Override
    public void failed(Exception ex) {
      delegate.failed(ex);
    }

    @Override
    public boolean isRepeatable() {
      return delegate.isRepeatable();
    }

    @Override
    public void resetRequest() throws IOException {
      delegate.resetRequest();
    }

    @Override
    public void close() throws IOException {
      delegate.close();
    }
  }

  public static class DelegatingResponseConsumer implements HttpAsyncResponseConsumer {
    final Span span;
    final HttpAsyncResponseConsumer delegate;

    public DelegatingResponseConsumer(final Span span, final HttpAsyncResponseConsumer delegate) {
      this.span = span;
      this.delegate = delegate;
    }

    @Override
    public void responseReceived(final HttpResponse response) throws IOException, HttpException {
      if (delegate != null) {
        delegate.responseReceived(response);
      }
    }

    @Override
    public void consumeContent(final ContentDecoder decoder, final IOControl ioctrl)
        throws IOException {
      if (delegate != null) {
        delegate.consumeContent(decoder, ioctrl);
      }
    }

    @Override
    public void responseCompleted(final HttpContext context) {
      if (delegate != null) {
        delegate.responseCompleted(context);
      }
    }

    @Override
    public void failed(final Exception ex) {
      if (delegate != null) {
        delegate.failed(ex);
      }
    }

    @Override
    public Exception getException() {
      if (delegate != null) {
        return delegate.getException();
      } else {
        return null;
      }
    }

    @Override
    public Object getResult() {
      if (delegate != null) {
        return delegate.getResult();
      } else {
        return null;
      }
    }

    @Override
    public boolean isDone() {
      if (delegate != null) {
        return delegate.isDone();
      } else {
        return true;
      }
    }

    @Override
    public void close() throws IOException {
      if (delegate != null) {
        delegate.close();
      }
    }

    @Override
    public boolean cancel() {
      if (delegate != null) {
        return delegate.cancel();
      } else {
        return true;
      }
    }
  }

  public static class TraceContinuedFutureCallback<T> implements FutureCallback<T> {
    private final Context parentContext;
    private final Span clientSpan;
    private final HttpContext context;
    private final FutureCallback<T> delegate;

    public TraceContinuedFutureCallback(
        Context parentContext, Span clientSpan, HttpContext context, FutureCallback<T> delegate) {
      this.parentContext = parentContext;
      this.clientSpan = clientSpan;
      this.context = context;
      // Note: this can be null in real life, so we have to handle this carefully
      this.delegate = delegate;
    }

    @Override
    public void completed(T result) {
      tracer().end(clientSpan, getResponse(context));

      if (parentContext == null) {
        completeDelegate(result);
      } else {
        try (Scope scope = parentContext.makeCurrent()) {
          completeDelegate(result);
        }
      }
    }

    @Override
    public void failed(Exception ex) {
      // end span before calling delegate
      tracer().endExceptionally(clientSpan, getResponse(context), ex);

      if (parentContext == null) {
        failDelegate(ex);
      } else {
        try (Scope scope = parentContext.makeCurrent()) {
          failDelegate(ex);
        }
      }
    }

    @Override
    public void cancelled() {
      // end span before calling delegate
      tracer().end(clientSpan, getResponse(context));

      if (parentContext == null) {
        cancelDelegate();
      } else {
        try (Scope scope = parentContext.makeCurrent()) {
          cancelDelegate();
        }
      }
    }

    private void completeDelegate(T result) {
      if (delegate != null) {
        delegate.completed(result);
      }
    }

    private void failDelegate(Exception ex) {
      if (delegate != null) {
        delegate.failed(ex);
      }
    }

    private void cancelDelegate() {
      if (delegate != null) {
        delegate.cancelled();
      }
    }

    private static HttpResponse getResponse(HttpContext context) {
      return (HttpResponse) context.getAttribute(HttpCoreContext.HTTP_RESPONSE);
    }
  }
}
