/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.awslambda.v1_0

import static io.opentelemetry.api.trace.Span.Kind.CONSUMER
import static io.opentelemetry.api.trace.Span.Kind.SERVER

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.events.SQSEvent
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.context.propagation.DefaultContextPropagators
import io.opentelemetry.extension.trace.propagation.AwsXRayPropagator
import io.opentelemetry.instrumentation.test.InstrumentationSpecification
import io.opentelemetry.instrumentation.test.InstrumentationTestTrait
import io.opentelemetry.api.trace.attributes.SemanticAttributes
import io.opentelemetry.api.trace.propagation.HttpTraceContext

class AwsLambdaSqsMessageHandlerTest extends InstrumentationSpecification implements InstrumentationTestTrait {

  // Lambda instrumentation requires XRay propagator to be enabled.
  static {
    def propagators = DefaultContextPropagators.builder()
      .addTextMapPropagator(HttpTraceContext.instance)
      .addTextMapPropagator(AwsXRayPropagator.instance)
      .build()
    OpenTelemetry.setGlobalPropagators(propagators)
  }

  private static final String AWS_TRACE_HEADER1 = "Root=1-5759e988-bd862e3fe1be46a994272793;Parent=53995c3f42cd8ad8;Sampled=1"
  private static final String AWS_TRACE_HEADER2 = "Root=1-5759e988-bd862e3fe1be46a994272793;Parent=53995c3f42cd8ad9;Sampled=1"

  class TestHandler extends TracingSQSMessageHandler {
    @Override
    protected void handleMessage(SQSEvent.SQSMessage event, Context context) {
    }
  }

  def "messages with process spans"() {
    when:
    def context = Mock(Context)
    context.getFunctionName() >> "my_function"
    context.getAwsRequestId() >> "1-22-333"

    def message1 = new SQSEvent.SQSMessage()
    message1.setAttributes(["AWSTraceHeader": AWS_TRACE_HEADER1])
    message1.setMessageId("message1")
    message1.setEventSource("queue1")

    def message2 = new SQSEvent.SQSMessage()
    message2.setAttributes(["AWSTraceHeader": AWS_TRACE_HEADER2])
    message2.setMessageId("message2")
    message2.setEventSource("queue1")

    def event = new SQSEvent()
    event.setRecords([message1, message2])

    new TestHandler().handleRequest(event, context)

    then:
    assertTraces(1) {
      trace(0, 4) {
        span(0) {
          name("my_function")
          kind SERVER
          attributes {
            "${SemanticAttributes.FAAS_EXECUTION.key}" "1-22-333"
          }
        }
        span(1) {
          name("queue1 process")
          kind CONSUMER
          parentSpanId(span(0).spanId)
          attributes {
            "${SemanticAttributes.MESSAGING_SYSTEM.key}" "AmazonSQS"
            "${SemanticAttributes.MESSAGING_OPERATION.key}" "process"
          }
          hasLink("5759e988bd862e3fe1be46a994272793", "53995c3f42cd8ad8")
          hasLink("5759e988bd862e3fe1be46a994272793", "53995c3f42cd8ad9")
        }
        span(2) {
          name("queue1 process")
          kind CONSUMER
          parentSpanId(span(1).spanId)
          attributes {
            "${SemanticAttributes.MESSAGING_SYSTEM.key}" "AmazonSQS"
            "${SemanticAttributes.MESSAGING_OPERATION.key}" "process"
            "${SemanticAttributes.MESSAGING_MESSAGE_ID.key}" "message1"
            "${SemanticAttributes.MESSAGING_DESTINATION.key}" "queue1"
          }
          hasLink("5759e988bd862e3fe1be46a994272793", "53995c3f42cd8ad8")
        }
        span(3) {
          name("queue1 process")
          kind CONSUMER
          parentSpanId(span(1).spanId)
          attributes {
            "${SemanticAttributes.MESSAGING_SYSTEM.key}" "AmazonSQS"
            "${SemanticAttributes.MESSAGING_OPERATION.key}" "process"
            "${SemanticAttributes.MESSAGING_MESSAGE_ID.key}" "message2"
            "${SemanticAttributes.MESSAGING_DESTINATION.key}" "queue1"
          }
          hasLink("5759e988bd862e3fe1be46a994272793", "53995c3f42cd8ad9")
        }
      }
    }
  }
}
