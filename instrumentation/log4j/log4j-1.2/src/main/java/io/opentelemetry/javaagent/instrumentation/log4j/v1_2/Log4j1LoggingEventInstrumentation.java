/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.log4j.v1_2;

import static io.opentelemetry.instrumentation.api.log.LoggingContextConstants.SAMPLED;
import static io.opentelemetry.instrumentation.api.log.LoggingContextConstants.SPAN_ID;
import static io.opentelemetry.instrumentation.api.log.LoggingContextConstants.TRACE_ID;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.javaagent.instrumentation.api.InstrumentationContext;
import io.opentelemetry.javaagent.tooling.Instrumenter;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.log4j.MDC;
import org.apache.log4j.spi.LoggingEvent;

@AutoService(Instrumenter.class)
public class Log4j1LoggingEventInstrumentation extends Instrumenter.Default {
  public Log4j1LoggingEventInstrumentation() {
    super("log4j1", "log4j");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named("org.apache.log4j.spi.LoggingEvent");
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("org.apache.log4j.spi.LoggingEvent", Span.class.getName());
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    Map<ElementMatcher.Junction<MethodDescription>, String> transformers = new HashMap<>();

    transformers.put(
        isMethod()
            .and(isPublic())
            .and(named("getMDC"))
            .and(takesArguments(1))
            .and(takesArgument(0, String.class)),
        Log4j1LoggingEventInstrumentation.class.getName() + "$GetMdcAdvice");

    transformers.put(
        isMethod().and(isPublic()).and(named("getMDCCopy")).and(takesArguments(0)),
        Log4j1LoggingEventInstrumentation.class.getName() + "$GetMdcCopyAdvice");

    return transformers;
  }

  public static class GetMdcAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
        @Advice.This LoggingEvent event,
        @Advice.Argument(0) String key,
        @Advice.Return(readOnly = false) Object value) {
      if (TRACE_ID.equals(key) || SPAN_ID.equals(key) || SAMPLED.equals(key)) {
        if (value != null) {
          // Assume already instrumented event if traceId/spanId/sampled is present.
          return;
        }

        Span span = InstrumentationContext.get(LoggingEvent.class, Span.class).get(event);
        if (span == null || !span.getSpanContext().isValid()) {
          return;
        }

        SpanContext spanContext = span.getSpanContext();
        switch (key) {
          case TRACE_ID:
            value = spanContext.getTraceIdAsHexString();
            break;
          case SPAN_ID:
            value = spanContext.getSpanIdAsHexString();
            break;
          case SAMPLED:
            value = Boolean.toString(spanContext.isSampled());
            break;
        }
      }
    }
  }

  public static class GetMdcCopyAdvice {
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
        @Advice.This LoggingEvent event,
        @Advice.FieldValue(value = "mdcCopyLookupRequired", readOnly = false) boolean copyRequired,
        @Advice.FieldValue(value = "mdcCopy", readOnly = false) Hashtable mdcCopy) {
      // this advice basically replaces the original method

      if (copyRequired) {
        copyRequired = false;

        Hashtable mdc = new Hashtable();

        Hashtable originalMdc = MDC.getContext();
        if (originalMdc != null) {
          mdc.putAll(originalMdc);
        }

        // Assume already instrumented event if traceId is present.
        if (!mdc.contains(TRACE_ID)) {
          Span span = InstrumentationContext.get(LoggingEvent.class, Span.class).get(event);
          if (span != null && span.getSpanContext().isValid()) {
            SpanContext spanContext = span.getSpanContext();
            mdc.put(TRACE_ID, spanContext.getTraceIdAsHexString());
            mdc.put(SPAN_ID, spanContext.getSpanIdAsHexString());
            mdc.put(SAMPLED, Boolean.toString(spanContext.isSampled()));
          }
        }

        mdcCopy = mdc;
      }
    }
  }
}
