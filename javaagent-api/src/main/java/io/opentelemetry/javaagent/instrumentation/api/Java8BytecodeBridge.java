/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.api;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.ContextPropagators;

/**
 * A helper for accessing methods that rely on new Java 8 bytecode features such as calling a static
 * interface methods. In instrumentation, we may need to call these methods in code that is inlined
 * into an instrumented class, however many times the instrumented class has been compiled to a
 * previous version of bytecode and so we cannot inline calls to static interface methods, as those
 * were not supported prior to Java 8 and will lead to a class verification error.
 */
public final class Java8BytecodeBridge {

  /** Calls {@link Context#current()}. */
  public static Context currentContext() {
    return Context.current();
  }

  /** Calls {@link Span#current()}. */
  public static Span currentSpan() {
    return Span.current();
  }

  /** Calls {@link Span#fromContext(Context)}. */
  public static Span spanFromContext(Context context) {
    return Span.fromContext(context);
  }

  public static ContextPropagators getGlobalPropagators() {
    return OpenTelemetry.getGlobalPropagators();
  }

  /** Calls {@link OpenTelemetry#getGlobalTracer(String)} */
  public static Tracer getGlobalTracer(String name) {
    return OpenTelemetry.getGlobalTracer(name);
  }
}
