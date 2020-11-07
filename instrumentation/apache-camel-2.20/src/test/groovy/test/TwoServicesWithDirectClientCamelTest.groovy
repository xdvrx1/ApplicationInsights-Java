/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package test

import static io.opentelemetry.api.trace.Span.Kind.CLIENT
import static io.opentelemetry.api.trace.Span.Kind.INTERNAL
import static io.opentelemetry.api.trace.Span.Kind.SERVER

import com.google.common.collect.ImmutableMap
import io.opentelemetry.api.trace.attributes.SemanticAttributes
import io.opentelemetry.instrumentation.api.aiappid.AiAppId
import io.opentelemetry.instrumentation.test.AgentTestRunner
import io.opentelemetry.instrumentation.test.utils.PortUtils
import org.apache.camel.CamelContext
import org.apache.camel.ProducerTemplate
import org.apache.camel.builder.RouteBuilder
import org.apache.camel.impl.DefaultCamelContext
import org.springframework.boot.SpringApplication
import org.springframework.context.ConfigurableApplicationContext
import spock.lang.Shared

class TwoServicesWithDirectClientCamelTest extends AgentTestRunner {

  @Shared
  int portOne
  @Shared
  int portTwo
  @Shared
  ConfigurableApplicationContext server
  @Shared
  CamelContext clientContext

  def setupSpec() {
    withRetryOnAddressAlreadyInUse({
      setupSpecUnderRetry()
    })
  }

  def setupSpecUnderRetry() {
    portOne = PortUtils.randomOpenPort()
    portTwo = PortUtils.randomOpenPort()
    def app = new SpringApplication(TwoServicesConfig)
    app.setDefaultProperties(ImmutableMap.of("service.one.port", portOne, "service.two.port", portTwo))
    server = app.run()
  }

  def createAndStartClient() {
    clientContext = new DefaultCamelContext()
    clientContext.addRoutes(new RouteBuilder() {
      void configure() {
        from("direct:input")
          .log("SENT Client request")
          .to("http://localhost:$portOne/serviceOne")
          .log("RECEIVED Client response")
      }
    })
    clientContext.start()
  }

  def cleanupSpec() {
    if (server != null) {
      server.close()
      server = null
    }
  }

  def "two camel service spans"() {
    setup:
    createAndStartClient()
    ProducerTemplate template = clientContext.createProducerTemplate()

    when:
    template.sendBody("direct:input", "Example request")

    then:
    assertTraces(1) {
      trace(0, 8) {
        it.span(0) {
          name "input"
          kind INTERNAL
          attributes {
            "camel.uri" "direct://input"
          }
        }
        it.span(1) {
          name "POST"
          kind CLIENT
          attributes {
            "$SemanticAttributes.HTTP_METHOD.key" "POST"
            "$SemanticAttributes.HTTP_URL.key" "http://localhost:$portOne/serviceOne"
            "$SemanticAttributes.HTTP_STATUS_CODE.key" 200
            "camel.uri" "http://localhost:$portOne/serviceOne"
          }
        }
        it.span(2) {
          name "HTTP POST"
          kind CLIENT
          attributes {
            "$SemanticAttributes.HTTP_METHOD.key" "POST"
            "$SemanticAttributes.HTTP_URL.key" "http://localhost:$portOne/serviceOne"
            "$SemanticAttributes.HTTP_STATUS_CODE.key" 200
            "$SemanticAttributes.NET_PEER_NAME.key" "localhost"
            "$SemanticAttributes.NET_PEER_PORT.key" portOne
            "$SemanticAttributes.NET_TRANSPORT.key" "IP.TCP"
            "$SemanticAttributes.HTTP_FLAVOR.key" "1.1"
            "$AiAppId.SPAN_TARGET_ATTRIBUTE_NAME" AiAppId.getAppId()
          }
        }
        it.span(3) {
          name "/serviceOne"
          kind SERVER
          attributes {
            "$SemanticAttributes.HTTP_METHOD.key" "POST"
            "$SemanticAttributes.HTTP_URL.key" "http://localhost:$portOne/serviceOne"
            "$SemanticAttributes.HTTP_STATUS_CODE.key" 200
            "camel.uri" "http://0.0.0.0:$portOne/serviceOne"
          }
        }
        it.span(4) {
          name "POST"
          kind CLIENT
          attributes {
            "$SemanticAttributes.HTTP_METHOD.key" "POST"
            "$SemanticAttributes.HTTP_URL.key" "http://0.0.0.0:$portTwo/serviceTwo"
            "$SemanticAttributes.HTTP_STATUS_CODE.key" 200
            "camel.uri" "http://0.0.0.0:$portTwo/serviceTwo"
          }
        }
        it.span(5) {
          name "HTTP POST"
          kind CLIENT
          attributes {
            "$SemanticAttributes.HTTP_METHOD.key" "POST"
            "$SemanticAttributes.HTTP_URL.key" "http://0.0.0.0:$portTwo/serviceTwo"
            "$SemanticAttributes.HTTP_STATUS_CODE.key" 200
            "$SemanticAttributes.NET_PEER_NAME.key" "0.0.0.0"
            "$SemanticAttributes.NET_PEER_PORT.key" portTwo
            "$SemanticAttributes.NET_TRANSPORT.key" "IP.TCP"
            "$SemanticAttributes.HTTP_FLAVOR.key" "1.1"
            "$SemanticAttributes.HTTP_USER_AGENT.key" "Jakarta Commons-HttpClient/3.1"
            "$AiAppId.SPAN_TARGET_ATTRIBUTE_NAME" AiAppId.getAppId()
          }
        }
        it.span(6) {
          name "/serviceTwo"
          kind SERVER
          attributes {
            "$SemanticAttributes.HTTP_METHOD.key" "POST"
            "$SemanticAttributes.HTTP_STATUS_CODE.key" 200
            "$SemanticAttributes.HTTP_URL.key" "http://0.0.0.0:$portTwo/serviceTwo"
            "$SemanticAttributes.NET_PEER_PORT.key" Number
            "$SemanticAttributes.NET_PEER_IP.key" InetAddress.getLocalHost().getHostAddress().toString()
            "$SemanticAttributes.HTTP_USER_AGENT.key" "Jakarta Commons-HttpClient/3.1"
            "$SemanticAttributes.HTTP_FLAVOR.key" "HTTP/1.1"
            "$SemanticAttributes.HTTP_CLIENT_IP.key" InetAddress.getLocalHost().getHostAddress().toString()
          }
        }
        it.span(7) {
          name "/serviceTwo"
          kind INTERNAL
          attributes {
            "$SemanticAttributes.HTTP_METHOD.key" "POST"
            "$SemanticAttributes.HTTP_URL.key" "http://0.0.0.0:$portTwo/serviceTwo"
            "camel.uri" "jetty:http://0.0.0.0:$portTwo/serviceTwo?arg=value"
          }
        }
      }
    }
  }
}
