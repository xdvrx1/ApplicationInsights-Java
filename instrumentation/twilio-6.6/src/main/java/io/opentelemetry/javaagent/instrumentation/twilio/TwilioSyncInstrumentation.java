/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.twilio;

import static io.opentelemetry.api.trace.Span.Kind.CLIENT;
import static io.opentelemetry.javaagent.instrumentation.twilio.TwilioClientDecorator.DECORATE;
import static io.opentelemetry.javaagent.instrumentation.twilio.TwilioClientDecorator.tracer;
import static io.opentelemetry.javaagent.tooling.ClassLoaderMatcher.hasClassesNamed;
import static io.opentelemetry.javaagent.tooling.bytebuddy.matcher.AgentElementMatchers.extendsClass;
import static io.opentelemetry.javaagent.tooling.matcher.NameMatchers.namedOneOf;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isAbstract;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.not;

import com.google.auto.service.AutoService;
import com.twilio.Twilio;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.javaagent.instrumentation.api.CallDepthThreadLocalMap;
import io.opentelemetry.javaagent.instrumentation.api.SpanWithScope;
import io.opentelemetry.javaagent.tooling.Instrumenter;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatcher;

/** Instrument the Twilio SDK to identify calls as a seperate service. */
@AutoService(Instrumenter.class)
public class TwilioSyncInstrumentation extends Instrumenter.Default {

  public TwilioSyncInstrumentation() {
    super("twilio-sdk");
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    // Optimization for expensive typeMatcher.
    return hasClassesNamed("com.twilio.Twilio");
  }

  /** Match any child class of the base Twilio service classes. */
  @Override
  public net.bytebuddy.matcher.ElementMatcher<
          ? super net.bytebuddy.description.type.TypeDescription>
      typeMatcher() {
    return extendsClass(
        namedOneOf(
            "com.twilio.base.Creator",
            "com.twilio.base.Deleter",
            "com.twilio.base.Fetcher",
            "com.twilio.base.Reader",
            "com.twilio.base.Updater"));
  }

  /** Return the helper classes which will be available for use in instrumentation. */
  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".TwilioClientDecorator",
    };
  }

  /** Return bytebuddy transformers for instrumenting the Twilio SDK. */
  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {

    /*
       We are listing out the main service calls on the Creator, Deleter, Fetcher, Reader, and
       Updater abstract classes. The isDeclaredBy() matcher did not work in the unit tests and
       we found that there were certain methods declared on the base class (particularly Reader),
       which we weren't interested in annotating.
    */

    return singletonMap(
        isMethod()
            .and(isPublic())
            .and(not(isAbstract()))
            .and(namedOneOf("create", "delete", "read", "fetch", "update")),
        TwilioSyncInstrumentation.class.getName() + "$TwilioClientAdvice");
  }

  /** Advice for instrumenting Twilio service classes. */
  public static class TwilioClientAdvice {

    /** Method entry instrumentation. */
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static SpanWithScope methodEnter(
        @Advice.This Object that, @Advice.Origin("#m") String methodName) {

      // Ensure that we only create a span for the top-level Twilio client method; except in the
      // case of async operations where we want visibility into how long the task was delayed from
      // starting. Our call depth checker does not span threads, so the async case is handled
      // automatically for us.
      int callDepth = CallDepthThreadLocalMap.incrementCallDepth(Twilio.class);
      if (callDepth > 0) {
        return null;
      }

      Span span =
          tracer()
              .spanBuilder(DECORATE.spanNameOnServiceExecution(that, methodName))
              .setSpanKind(CLIENT)
              .startSpan();
      DECORATE.afterStart(span);

      return new SpanWithScope(span, span.makeCurrent());
    }

    /** Method exit instrumentation. */
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void methodExit(
        @Advice.Enter SpanWithScope spanWithScope,
        @Advice.Thrown Throwable throwable,
        @Advice.Return Object response) {
      if (spanWithScope == null) {
        return;
      }
      CallDepthThreadLocalMap.reset(Twilio.class);

      // If we have a scope (i.e. we were the top-level Twilio SDK invocation),
      try {
        Span span = spanWithScope.getSpan();

        DECORATE.onResult(span, response);
        DECORATE.onError(span, throwable);
        DECORATE.beforeFinish(span);
        span.end();
      } finally {
        spanWithScope.closeScope();
      }
    }
  }
}
