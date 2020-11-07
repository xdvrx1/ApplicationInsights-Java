/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.grizzly.client;

import static io.opentelemetry.javaagent.instrumentation.grizzly.client.GrizzlyClientTracer.tracer;

import com.ning.http.client.AsyncCompletionHandler;
import com.ning.http.client.AsyncHandler;
import com.ning.http.client.Response;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.javaagent.instrumentation.api.ContextStore;
import io.opentelemetry.javaagent.instrumentation.api.InstrumentationContext;
import io.opentelemetry.javaagent.instrumentation.api.Pair;
import net.bytebuddy.asm.Advice;

public class GrizzlyClientResponseAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static Scope onEnter(
      @Advice.This AsyncCompletionHandler<?> handler, @Advice.Argument(0) Response response) {

    // TODO I think all this should happen on exit, not on enter.
    // After response was handled by user provided handler.
    ContextStore<AsyncHandler, Pair> contextStore =
        InstrumentationContext.get(AsyncHandler.class, Pair.class);
    Pair<Context, Span> spanWithParent = contextStore.get(handler);
    if (null != spanWithParent) {
      contextStore.put(handler, null);
    }
    if (spanWithParent.hasRight()) {
      tracer().end(spanWithParent.getRight(), response);
    }
    return spanWithParent.hasLeft() ? spanWithParent.getLeft().makeCurrent() : null;
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void onExit(@Advice.Enter Scope scope) {
    if (null != scope) {
      scope.close();
    }
  }
}
