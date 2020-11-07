/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.awslambda.v1_0;

import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.amazonaws.services.lambda.runtime.events.SQSEvent.SQSMessage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Span.Kind;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.attributes.SemanticAttributes;
import io.opentelemetry.context.Scope;
import io.opentelemetry.instrumentation.api.tracer.BaseTracer;

public class AwsLambdaMessageTracer extends BaseTracer {

  private static final String AWS_TRACE_HEADER_SQS_ATTRIBUTE_KEY = "AWSTraceHeader";

  public AwsLambdaMessageTracer() {}

  public AwsLambdaMessageTracer(Tracer tracer) {
    super(tracer);
  }

  public Span startSpan(com.amazonaws.services.lambda.runtime.Context context, SQSEvent event) {
    // Use event source in name if all messages have the same source, otherwise use placeholder.
    String source = "multiple_sources";
    if (!event.getRecords().isEmpty()) {
      String messageSource = event.getRecords().get(0).getEventSource();
      for (int i = 1; i < event.getRecords().size(); i++) {
        SQSMessage message = event.getRecords().get(i);
        if (!message.getEventSource().equals(messageSource)) {
          messageSource = null;
          break;
        }
      }
      if (messageSource != null) {
        source = messageSource;
      }
    }

    Span.Builder span = tracer.spanBuilder(source + " process").setSpanKind(Kind.CONSUMER);

    span.setAttribute(SemanticAttributes.MESSAGING_SYSTEM, "AmazonSQS");
    span.setAttribute(SemanticAttributes.MESSAGING_OPERATION, "process");

    for (SQSMessage message : event.getRecords()) {
      addLinkToMessageParent(message, span);
    }

    return span.startSpan();
  }

  public Span startSpan(SQSMessage message) {
    Span.Builder span =
        tracer.spanBuilder(message.getEventSource() + " process").setSpanKind(Kind.CONSUMER);

    span.setAttribute(SemanticAttributes.MESSAGING_SYSTEM, "AmazonSQS");
    span.setAttribute(SemanticAttributes.MESSAGING_OPERATION, "process");
    span.setAttribute(SemanticAttributes.MESSAGING_MESSAGE_ID, message.getMessageId());
    span.setAttribute(SemanticAttributes.MESSAGING_DESTINATION, message.getEventSource());

    addLinkToMessageParent(message, span);

    return span.startSpan();
  }

  public Scope startScope(Span span) {
    return span.makeCurrent();
  }

  private void addLinkToMessageParent(SQSMessage message, Span.Builder span) {
    String parentHeader = message.getAttributes().get(AWS_TRACE_HEADER_SQS_ATTRIBUTE_KEY);
    if (parentHeader != null) {
      SpanContext parentCtx =
          Span.fromContext(AwsLambdaUtil.extractParent(parentHeader)).getSpanContext();
      if (parentCtx.isValid()) {
        span.addLink(parentCtx);
      }
    }
  }

  @Override
  protected String getInstrumentationName() {
    return "io.opentelemetry.aws-lambda";
  }
}
