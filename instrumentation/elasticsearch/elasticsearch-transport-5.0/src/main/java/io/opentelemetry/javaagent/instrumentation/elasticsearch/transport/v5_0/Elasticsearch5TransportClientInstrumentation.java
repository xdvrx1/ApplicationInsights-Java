/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.elasticsearch.transport.v5_0;

import static io.opentelemetry.javaagent.instrumentation.elasticsearch.transport.ElasticsearchTransportClientTracer.tracer;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.javaagent.tooling.Instrumenter;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.elasticsearch.action.Action;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;

@AutoService(Instrumenter.class)
public class Elasticsearch5TransportClientInstrumentation extends Instrumenter.Default {

  public Elasticsearch5TransportClientInstrumentation() {
    super("elasticsearch", "elasticsearch-transport", "elasticsearch-transport-5");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    // If we want to be more generic, we could instrument the interface instead:
    // .and(safeHasSuperType(named("org.elasticsearch.client.ElasticsearchClient"))))
    return named("org.elasticsearch.client.support.AbstractClient");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "com.google.common.base.Preconditions",
      "com.google.common.base.Joiner",
      "com.google.common.base.Joiner$1",
      "com.google.common.base.Joiner$2",
      "com.google.common.base.Joiner$MapJoiner",
      "io.opentelemetry.javaagent.instrumentation.elasticsearch.transport.ElasticsearchTransportClientTracer",
      packageName + ".TransportActionListener",
    };
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        isMethod()
            .and(named("execute"))
            .and(takesArgument(0, named("org.elasticsearch.action.Action")))
            .and(takesArgument(1, named("org.elasticsearch.action.ActionRequest")))
            .and(takesArgument(2, named("org.elasticsearch.action.ActionListener"))),
        Elasticsearch5TransportClientInstrumentation.class.getName()
            + "$ElasticsearchTransportClientAdvice");
  }

  public static class ElasticsearchTransportClientAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
        @Advice.Argument(0) Action action,
        @Advice.Argument(1) ActionRequest actionRequest,
        @Advice.Local("otelSpan") Span span,
        @Advice.Local("otelScope") Scope scope,
        @Advice.Argument(value = 2, readOnly = false)
            ActionListener<ActionResponse> actionListener) {

      span = tracer().startSpan(null, action);
      scope = tracer().startScope(span);

      tracer().onRequest(span, action.getClass(), actionRequest.getClass());
      actionListener = new TransportActionListener<>(actionRequest, actionListener, span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.Thrown Throwable throwable,
        @Advice.Local("otelSpan") Span span,
        @Advice.Local("otelScope") Scope scope) {
      scope.close();

      if (throwable != null) {
        tracer().endExceptionally(span, throwable);
      }
    }
  }
}
