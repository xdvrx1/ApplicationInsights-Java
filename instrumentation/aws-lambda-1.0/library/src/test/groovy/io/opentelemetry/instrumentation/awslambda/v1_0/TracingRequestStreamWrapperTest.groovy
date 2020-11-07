/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.awslambda.v1_0

import static io.opentelemetry.api.trace.Span.Kind.SERVER

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestStreamHandler
import io.opentelemetry.instrumentation.test.InstrumentationSpecification
import io.opentelemetry.instrumentation.test.InstrumentationTestTrait
import io.opentelemetry.api.trace.attributes.SemanticAttributes
import java.nio.charset.Charset
import org.junit.Rule
import org.junit.contrib.java.lang.system.EnvironmentVariables
import spock.lang.Shared

class TracingRequestStreamWrapperTest extends InstrumentationSpecification implements InstrumentationTestTrait {

  @Rule
  public final EnvironmentVariables environmentVariables = new EnvironmentVariables()

  static class TestRequestHandler implements RequestStreamHandler {

    @Override
    void handleRequest(InputStream input, OutputStream output, Context context) {

      BufferedReader reader = new BufferedReader(new InputStreamReader(input))
      BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(output))
      String line = reader.readLine()
      if (line == "hello") {
        writer.write("world")
        writer.flush()
        writer.close()
      } else {
        throw new IllegalArgumentException("bad argument")
      }
    }
  }

  @Shared
  TracingRequestStreamWrapper wrapper

  def childSetup() {
    environmentVariables.set(WrappedLambda.OTEL_LAMBDA_HANDLER_ENV_KEY, "io.opentelemetry.instrumentation.awslambda.v1_0.TracingRequestStreamWrapperTest\$TestRequestHandler::handleRequest")
    wrapper = new TracingRequestStreamWrapper()
  }

  def "handler traced"() {
    when:
    def context = Mock(Context)
    context.getFunctionName() >> "my_function"
    context.getAwsRequestId() >> "1-22-333"
    def input = new ByteArrayInputStream("hello\n".getBytes(Charset.defaultCharset()))
    def output = new ByteArrayOutputStream()

    wrapper.handleRequest(input, output, context)

    then:
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
    def input = new ByteArrayInputStream("bye".getBytes(Charset.defaultCharset()))
    def output = new ByteArrayOutputStream()

    def thrown
    try {
      wrapper.handleRequest(input, output, context)
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

}
