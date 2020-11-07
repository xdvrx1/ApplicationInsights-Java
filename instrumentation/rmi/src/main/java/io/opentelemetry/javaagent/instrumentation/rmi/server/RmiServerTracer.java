/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.rmi.server;

import static io.opentelemetry.api.trace.Span.Kind.SERVER;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.attributes.SemanticAttributes;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapPropagator.Getter;
import io.opentelemetry.instrumentation.api.tracer.RpcServerTracer;
import java.lang.reflect.Method;

public class RmiServerTracer extends RpcServerTracer {
  private static final RmiServerTracer TRACER = new RmiServerTracer();

  public static RmiServerTracer tracer() {
    return TRACER;
  }

  public Span startSpan(Method method, Context context) {
    String serviceName = method.getDeclaringClass().getName();
    String methodName = method.getName();

    Span.Builder spanBuilder =
        tracer.spanBuilder(serviceName + "/" + methodName).setSpanKind(SERVER).setParent(context);
    spanBuilder.setAttribute(SemanticAttributes.RPC_SYSTEM, "java_rmi");
    spanBuilder.setAttribute(SemanticAttributes.RPC_SERVICE, serviceName);
    spanBuilder.setAttribute(SemanticAttributes.RPC_METHOD, methodName);

    return spanBuilder.startSpan();
  }

  @Override
  protected Getter getGetter() {
    return null;
  }

  @Override
  protected String getInstrumentationName() {
    return "io.opentelemetry.auto.rmi";
  }
}
