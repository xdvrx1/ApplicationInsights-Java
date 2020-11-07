/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.hystrix;

import static io.opentelemetry.api.trace.Span.Kind.INTERNAL;
import static io.opentelemetry.javaagent.instrumentation.hystrix.HystrixTracer.tracer;
import static io.opentelemetry.javaagent.tooling.ClassLoaderMatcher.hasClassesNamed;
import static io.opentelemetry.javaagent.tooling.bytebuddy.matcher.AgentElementMatchers.extendsClass;
import static io.opentelemetry.javaagent.tooling.matcher.NameMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.returns;

import com.google.auto.service.AutoService;
import com.netflix.hystrix.HystrixInvokableInfo;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.javaagent.instrumentation.rxjava.TracedOnSubscribe;
import io.opentelemetry.javaagent.tooling.Instrumenter;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import rx.Observable;

@AutoService(Instrumenter.class)
public class HystrixInstrumentation extends Instrumenter.Default {

  private static final String OPERATION_NAME = "hystrix.cmd";

  public HystrixInstrumentation() {
    super("hystrix");
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    // Optimization for expensive typeMatcher.
    return hasClassesNamed("com.netflix.hystrix.HystrixCommand");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return extendsClass(
        namedOneOf(
            "com.netflix.hystrix.HystrixCommand", "com.netflix.hystrix.HystrixObservableCommand"));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "rx.__OpenTelemetryTracingUtil",
      "io.opentelemetry.javaagent.instrumentation.rxjava.SpanFinishingSubscription",
      "io.opentelemetry.javaagent.instrumentation.rxjava.TracedSubscriber",
      "io.opentelemetry.javaagent.instrumentation.rxjava.TracedOnSubscribe",
      packageName + ".HystrixTracer",
      packageName + ".HystrixInstrumentation$HystrixOnSubscribe",
    };
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    Map<ElementMatcher.Junction<MethodDescription>, String> transformers = new HashMap<>();
    transformers.put(
        named("getExecutionObservable").and(returns(named("rx.Observable"))),
        HystrixInstrumentation.class.getName() + "$ExecuteAdvice");
    transformers.put(
        named("getFallbackObservable").and(returns(named("rx.Observable"))),
        HystrixInstrumentation.class.getName() + "$FallbackAdvice");
    return transformers;
  }

  public static class ExecuteAdvice {

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.This HystrixInvokableInfo<?> command,
        @Advice.Return(readOnly = false) Observable result,
        @Advice.Thrown Throwable throwable) {

      result = Observable.create(new HystrixOnSubscribe(result, command, "execute"));
    }
  }

  public static class FallbackAdvice {

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.This HystrixInvokableInfo<?> command,
        @Advice.Return(readOnly = false) Observable<?> result,
        @Advice.Thrown Throwable throwable) {

      result = Observable.create(new HystrixOnSubscribe(result, command, "fallback"));
    }
  }

  public static class HystrixOnSubscribe extends TracedOnSubscribe {
    private final HystrixInvokableInfo<?> command;
    private final String methodName;

    public HystrixOnSubscribe(
        Observable originalObservable, HystrixInvokableInfo<?> command, String methodName) {
      super(originalObservable, OPERATION_NAME, tracer(), INTERNAL);

      this.command = command;
      this.methodName = methodName;
    }

    @Override
    protected void decorateSpan(Span span) {
      tracer().onCommand(span, command, methodName);
    }
  }
}
