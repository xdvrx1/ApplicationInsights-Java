/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.jsp;

import static io.opentelemetry.javaagent.instrumentation.jsp.JSPTracer.tracer;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Span.Kind;
import io.opentelemetry.context.Scope;
import io.opentelemetry.javaagent.tooling.Instrumenter;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.jasper.JspCompilationContext;

@AutoService(Instrumenter.class)
public final class JasperJSPCompilationContextInstrumentation extends Instrumenter.Default {

  public JasperJSPCompilationContextInstrumentation() {
    super("jsp", "jsp-compile");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("org.apache.jasper.JspCompilationContext");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".JSPTracer",
    };
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        named("compile").and(takesArguments(0)).and(isPublic()),
        JasperJSPCompilationContextInstrumentation.class.getName()
            + "$JasperJspCompilationContext");
  }

  public static class JasperJspCompilationContext {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
        @Advice.This JspCompilationContext jspCompilationContext,
        @Advice.Local("otelSpan") Span span,
        @Advice.Local("otelScope") Scope scope) {
      span = tracer().startSpan(tracer().spanNameOnCompile(jspCompilationContext), Kind.INTERNAL);
      scope = span.makeCurrent();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.This JspCompilationContext jspCompilationContext,
        @Advice.Thrown Throwable throwable,
        @Advice.Local("otelSpan") Span span,
        @Advice.Local("otelScope") Scope scope) {
      scope.close();

      // Decorate on return because additional properties are available
      tracer().onCompile(span, jspCompilationContext);

      if (throwable != null) {
        tracer().endExceptionally(span, throwable);
      } else {
        tracer().end(span);
      }
    }
  }
}
