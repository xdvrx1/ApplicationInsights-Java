/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.awslambda.v1_0;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Span.Kind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;

/**
 * A base class similar to {@link RequestStreamHandler} but will automatically trace invocations of
 * {@link #doHandleRequest(InputStream input, OutputStream output, Context)}.
 */
public abstract class TracingRequestStreamHandler implements RequestStreamHandler {

  private final AwsLambdaTracer tracer;

  /**
   * Creates a new {@link TracingRequestStreamHandler} which traces using the default {@link
   * Tracer}.
   */
  protected TracingRequestStreamHandler() {
    this.tracer = new AwsLambdaTracer();
  }

  /**
   * Creates a new {@link TracingRequestStreamHandler} which traces using the specified {@link
   * Tracer}.
   */
  protected TracingRequestStreamHandler(Tracer tracer) {
    this.tracer = new AwsLambdaTracer(tracer);
  }

  /**
   * Creates a new {@link TracingRequestStreamHandler} which traces using the specified {@link
   * AwsLambdaTracer}.
   */
  protected TracingRequestStreamHandler(AwsLambdaTracer tracer) {
    this.tracer = tracer;
  }

  @Override
  public final void handleRequest(InputStream input, OutputStream output, Context context)
      throws IOException {
    Span span = tracer.startSpan(context, Kind.SERVER);
    try (Scope ignored = tracer.startScope(span)) {
      doHandleRequest(input, new OutputStreamWrapper(output, span), context);
    } catch (Throwable t) {
      tracer.endExceptionally(span, t);
      OpenTelemetrySdk.getGlobalTracerManagement().forceFlush().join(1, TimeUnit.SECONDS);
      throw t;
    }
  }

  protected abstract void doHandleRequest(InputStream input, OutputStream output, Context context)
      throws IOException;

  private class OutputStreamWrapper extends OutputStream {

    private final OutputStream delegate;
    private final Span span;

    OutputStreamWrapper(OutputStream delegate, Span span) {
      this.delegate = delegate;
      this.span = span;
    }

    @Override
    public void write(byte[] b) throws IOException {
      delegate.write(b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      delegate.write(b, off, len);
    }

    @Override
    public void flush() throws IOException {
      delegate.flush();
    }

    @Override
    public void close() throws IOException {
      delegate.close();
      tracer.end(span);
      OpenTelemetrySdk.getGlobalTracerManagement().forceFlush().join(1, TimeUnit.SECONDS);
    }

    @Override
    public void write(int b) throws IOException {
      delegate.write(b);
    }
  }
}
