/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.kubernetesclient;

import static io.opentelemetry.javaagent.instrumentation.kubernetesclient.KubernetesClientTracer.tracer;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.io.IOException;
import okhttp3.Interceptor;
import okhttp3.Response;

public class TracingInterceptor implements Interceptor {

  @Override
  public Response intercept(Chain chain) throws IOException {

    KubernetesRequestDigest digest = KubernetesRequestDigest.parse(chain.request());

    Span span = tracer().startSpan(digest);
    tracer().onRequest(span, chain.request());

    Context context = Context.current().with(span);

    Response response;
    try (Scope scope = context.makeCurrent()) {
      response = chain.proceed(chain.request());
    } catch (Exception e) {
      tracer().endExceptionally(span, e);
      throw e;
    }

    tracer().end(span, response);
    return response;
  }
}
