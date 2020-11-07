/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.awslambda.v1_0;

import com.amazonaws.services.lambda.runtime.Context;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Span.Kind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.attributes.SemanticAttributes;
import io.opentelemetry.context.Scope;
import io.opentelemetry.instrumentation.api.tracer.BaseTracer;

public class AwsLambdaTracer extends BaseTracer {

  private static final String AWS_TRACE_HEADER_ENV_KEY = "_X_AMZN_TRACE_ID";

  public AwsLambdaTracer() {}

  public AwsLambdaTracer(Tracer tracer) {
    super(tracer);
  }

  Span.Builder createSpan(Context context) {
    Span.Builder span = tracer.spanBuilder(context.getFunctionName());
    span.setAttribute(SemanticAttributes.FAAS_EXECUTION, context.getAwsRequestId());

    String parentTraceHeader = System.getenv(AWS_TRACE_HEADER_ENV_KEY);
    if (parentTraceHeader != null) {
      span.setParent(AwsLambdaUtil.extractParent(parentTraceHeader));
    }

    return span;
  }

  public Span startSpan(Context context, Kind kind) {
    return createSpan(context).setSpanKind(kind).startSpan();
  }

  /** Creates new scoped context with the given span. */
  public Scope startScope(Span span) {
    // TODO we could do this in one go, but TracingContextUtils.CONTEXT_SPAN_KEY is private
    io.opentelemetry.context.Context newContext =
        io.opentelemetry.context.Context.current().with(CONTEXT_SERVER_SPAN_KEY, span).with(span);
    return newContext.makeCurrent();
  }

  @Override
  protected String getInstrumentationName() {
    return "io.opentelemetry.aws-lambda";
  }
}
