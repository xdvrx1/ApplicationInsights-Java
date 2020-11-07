/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.springwebmvc;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.api.servlet.ServletContextPath;
import io.opentelemetry.instrumentation.api.tracer.BaseTracer;
import java.lang.reflect.Method;
import javax.servlet.Servlet;
import javax.servlet.http.HttpServletRequest;
import org.springframework.web.HttpRequestHandler;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.View;
import org.springframework.web.servlet.mvc.Controller;

public class SpringWebMvcTracer extends BaseTracer {

  private static final SpringWebMvcTracer TRACER = new SpringWebMvcTracer();

  public static SpringWebMvcTracer tracer() {
    return TRACER;
  }

  public Span startHandlerSpan(Object handler) {
    return tracer.spanBuilder(spanNameOnHandle(handler)).startSpan();
  }

  public Span startSpan(ModelAndView mv) {
    Span span = tracer.spanBuilder(spanNameOnRender(mv)).startSpan();
    onRender(span, mv);
    return span;
  }

  public void onRequest(Context context, Span span, HttpServletRequest request) {
    if (request != null) {
      Object bestMatchingPattern =
          request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
      if (bestMatchingPattern != null) {
        span.updateName(ServletContextPath.prepend(context, bestMatchingPattern.toString()));
      }
    }
  }

  private String spanNameOnHandle(Object handler) {
    Class<?> clazz;
    String methodName;

    if (handler instanceof HandlerMethod) {
      // name span based on the class and method name defined in the handler
      Method method = ((HandlerMethod) handler).getMethod();
      clazz = method.getDeclaringClass();
      methodName = method.getName();
    } else if (handler instanceof HttpRequestHandler) {
      // org.springframework.web.servlet.mvc.HttpRequestHandlerAdapter
      clazz = handler.getClass();
      methodName = "handleRequest";
    } else if (handler instanceof Controller) {
      // org.springframework.web.servlet.mvc.SimpleControllerHandlerAdapter
      clazz = handler.getClass();
      methodName = "handleRequest";
    } else if (handler instanceof Servlet) {
      // org.springframework.web.servlet.handler.SimpleServletHandlerAdapter
      clazz = handler.getClass();
      methodName = "service";
    } else {
      // perhaps org.springframework.web.servlet.mvc.annotation.AnnotationMethodHandlerAdapter
      clazz = handler.getClass();
      methodName = "<annotation>";
    }

    return spanNameForMethod(clazz, methodName);
  }

  private String spanNameOnRender(ModelAndView mv) {
    String viewName = mv.getViewName();
    if (viewName != null) {
      return "Render " + viewName;
    }
    View view = mv.getView();
    if (view != null) {
      return "Render " + view.getClass().getSimpleName();
    }
    // either viewName or view should be non-null, but just in case
    return "Render <unknown>";
  }

  private void onRender(Span span, ModelAndView mv) {
    span.setAttribute("view.name", mv.getViewName());
    View view = mv.getView();
    if (view != null) {
      span.setAttribute("view.type", spanNameForClass(view.getClass()));
    }
  }

  @Override
  protected String getInstrumentationName() {
    return "io.opentelemetry.auto.spring-webmvc";
  }
}
