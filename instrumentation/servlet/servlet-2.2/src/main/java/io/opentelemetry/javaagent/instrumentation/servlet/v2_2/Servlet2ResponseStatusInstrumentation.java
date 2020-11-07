/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.servlet.v2_2;

import static io.opentelemetry.javaagent.tooling.ClassLoaderMatcher.hasClassesNamed;
import static io.opentelemetry.javaagent.tooling.bytebuddy.matcher.AgentElementMatchers.safeHasSuperType;
import static io.opentelemetry.javaagent.tooling.matcher.NameMatchers.namedOneOf;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;

import com.google.auto.service.AutoService;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.javaagent.instrumentation.api.InstrumentationContext;
import io.opentelemetry.javaagent.tooling.Instrumenter;
import java.util.HashMap;
import java.util.Map;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Class <code>javax.servlet.http.HttpServletResponse</code> got method <code>getStatus</code> only
 * in Servlet specification version 3.0. This means that we cannot set {@link
 * io.opentelemetry.api.trace.attributes.SemanticAttributes#HTTP_STATUS_CODE} attribute on the
 * created span using just response object.
 *
 * <p>This instrumentation intercepts status setting methods from Servlet 2.0 specification and
 * stores that status into context store. Then {@link Servlet2Advice#stopSpan(ServletRequest,
 * ServletResponse, Throwable, Span, Scope)} can get it from context and set required span
 * attribute.
 */
@AutoService(Instrumenter.class)
public final class Servlet2ResponseStatusInstrumentation extends Instrumenter.Default {
  public Servlet2ResponseStatusInstrumentation() {
    super("servlet", "servlet-2");
  }

  // this is required to make sure servlet 2 instrumentation won't apply to servlet 3
  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    return not(hasClassesNamed("javax.servlet.AsyncEvent", "javax.servlet.AsyncListener"));
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return safeHasSuperType(named("javax.servlet.http.HttpServletResponse"));
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("javax.servlet.ServletResponse", Integer.class.getName());
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    Map<ElementMatcher<? super MethodDescription>, String> transformers = new HashMap<>();
    transformers.put(
        namedOneOf("sendError", "setStatus"),
        Servlet2ResponseStatusInstrumentation.class.getName() + "$Servlet2ResponseStatusAdvice");
    transformers.put(
        named("sendRedirect"),
        Servlet2ResponseStatusInstrumentation.class.getName() + "$Servlet2ResponseRedirectAdvice");
    return transformers;
  }

  public static class Servlet2ResponseRedirectAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.This HttpServletResponse response) {
      InstrumentationContext.get(ServletResponse.class, Integer.class).put(response, 302);
    }
  }

  public static class Servlet2ResponseStatusAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
        @Advice.This HttpServletResponse response, @Advice.Argument(0) Integer status) {
      InstrumentationContext.get(ServletResponse.class, Integer.class).put(response, status);
    }
  }
}
