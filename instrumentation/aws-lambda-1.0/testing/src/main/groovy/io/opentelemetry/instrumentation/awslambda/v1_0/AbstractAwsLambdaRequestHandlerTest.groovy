/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.awslambda.v1_0

import static io.opentelemetry.api.trace.Span.Kind.SERVER

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestHandler
import com.github.stefanbirkner.systemlambda.SystemLambda
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.context.propagation.DefaultContextPropagators
import io.opentelemetry.extension.trace.propagation.AwsXRayPropagator
import io.opentelemetry.instrumentation.test.InstrumentationSpecification
import io.opentelemetry.api.trace.attributes.SemanticAttributes
import io.opentelemetry.api.trace.propagation.HttpTraceContext

abstract class AbstractAwsLambdaRequestHandlerTest extends InstrumentationSpecification {

  // Lambda instrumentation requires XRay propagator to be enabled.
  static {
    def propagators = DefaultContextPropagators.builder()
      .addTextMapPropagator(HttpTraceContext.instance)
      .addTextMapPropagator(AwsXRayPropagator.instance)
      .build()
    OpenTelemetry.setGlobalPropagators(propagators)
  }

  protected static String doHandleRequest(String input, Context context) {
    if (input == "hello") {
      return "world"
    }
    throw new IllegalArgumentException("bad argument")
  }

  abstract RequestHandler<String, String> handler()

  def "handler traced"() {
    when:
    def context = Mock(Context)
    context.getFunctionName() >> "my_function"
    context.getAwsRequestId() >> "1-22-333"

    def result = handler().handleRequest("hello", context)

    then:
    result == "world"
    assertTraces(1) {
      trace(0, 1) {
        span(0) {
          name("my_function")
          kind SERVER
          attributes {
            "${SemanticAttributes.FAAS_EXECUTION.key}" "1-22-333"
          }
        }
      }
    }
  }

  def "handler traced with exception"() {
    when:
    def context = Mock(Context)
    context.getFunctionName() >> "my_function"
    context.getAwsRequestId() >> "1-22-333"

    def thrown
    try {
      handler().handleRequest("goodbye", context)
    } catch (Throwable t) {
      thrown = t
    }

    then:
    thrown != null
    assertTraces(1) {
      trace(0, 1) {
        span(0) {
          name("my_function")
          kind SERVER
          errored true
          errorEvent(IllegalArgumentException, "bad argument")
          attributes {
            "${SemanticAttributes.FAAS_EXECUTION.key}" "1-22-333"
          }
        }
      }
    }
  }

  def "handler links to lambda trace"() {
    when:
    def context = Mock(Context)
    context.getFunctionName() >> "my_function"
    context.getAwsRequestId() >> "1-22-333"

    def result
    SystemLambda.withEnvironmentVariable("_X_AMZN_TRACE_ID", "Root=1-8a3c60f7-d188f8fa79d48a391a778fa6;Parent=0000000000000456;Sampled=1")
      .execute({
        result = handler().handleRequest("hello", context)
      })

    then:
    result == "world"
    assertTraces(1) {
      trace(0, 1) {
        span(0) {
          name("my_function")
          kind SERVER
          parentSpanId("0000000000000456")
          traceId("8a3c60f7d188f8fa79d48a391a778fa6")
          attributes {
            "${SemanticAttributes.FAAS_EXECUTION.key}" "1-22-333"
          }
        }
      }
    }
  }
}
