/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.armeria.v1_0

import static io.opentelemetry.api.trace.Span.Kind.CLIENT
import static io.opentelemetry.api.trace.Span.Kind.SERVER

import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.client.WebClientBuilder
import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.server.ServerBuilder
import com.linecorp.armeria.testing.junit4.server.ServerRule
import io.opentelemetry.instrumentation.test.InstrumentationSpecification
import io.opentelemetry.api.trace.attributes.SemanticAttributes
import spock.lang.Shared
import spock.lang.Unroll

@Unroll
abstract class AbstractArmeriaTest extends InstrumentationSpecification {

  abstract ServerBuilder configureServer(ServerBuilder serverBuilder)

  abstract WebClientBuilder configureClient(WebClientBuilder clientBuilder)

  // We cannot annotate with @ClassRule since then Armeria will be class loaded before bytecode
  // instrumentation is set up by the Spock trait.
  @Shared
  protected ServerRule server = new ServerRule() {
    @Override
    protected void configure(ServerBuilder sb) throws Exception {
      sb = configureServer(sb)
      sb.service("/exact", { ctx, req -> HttpResponse.of(HttpStatus.OK) })
      sb.service("/items/{itemsId}", { ctx, req -> HttpResponse.of(HttpStatus.OK) })
      sb.service("/httperror", { ctx, req -> HttpResponse.of(HttpStatus.NOT_IMPLEMENTED) })
      sb.service("/exception", { ctx, req -> throw new IllegalStateException("illegal") })
    }
  }

  def client = configureClient(WebClient.builder(server.httpUri())).build()

  def "HTTP #method #path"() {
    when:
    def response = client.execute(HttpRequest.of(method, path)).aggregate().join()

    then:
    response.status().code() == code
    assertTraces(1) {
      trace(0, 2) {
        span(0) {
          name("HTTP ${method}")
          kind CLIENT
          errored code != 200
          hasNoParent()
          attributes {
            "${SemanticAttributes.NET_TRANSPORT.key()}" "IP.TCP"
            "${SemanticAttributes.NET_PEER_IP.key()}" "127.0.0.1"
            // TODO(anuraaga): peer name shouldn't be set to IP
            "${SemanticAttributes.NET_PEER_NAME.key()}" "127.0.0.1"
            "${SemanticAttributes.NET_PEER_PORT.key()}" Long
            "${SemanticAttributes.HTTP_URL.key()}" "${server.httpUri()}${path}"
            "${SemanticAttributes.HTTP_METHOD.key()}" method.name()
            "${SemanticAttributes.HTTP_STATUS_CODE.key()}" code
            "${SemanticAttributes.HTTP_FLAVOR.key()}" "http"
          }
        }
        span(1) {
          name(spanName)
          kind SERVER
          childOf span(0)
          errored code != 200
          if (path == "/exception") {
            errorEvent(IllegalStateException, "illegal")
          }
          attributes {
            "${SemanticAttributes.NET_PEER_IP.key()}" "127.0.0.1"
            "${SemanticAttributes.NET_PEER_PORT.key()}" Long
            "${SemanticAttributes.HTTP_URL.key()}" "${server.httpUri()}${path}"
            "${SemanticAttributes.HTTP_METHOD.key()}" method.name()
            "${SemanticAttributes.HTTP_STATUS_CODE.key()}" code
            "${SemanticAttributes.HTTP_FLAVOR.key()}" "h2c"
            "${SemanticAttributes.HTTP_USER_AGENT.key()}" String
            "${SemanticAttributes.HTTP_CLIENT_IP.key()}" "127.0.0.1"
          }
        }
      }
    }

    where:
    path          | spanName     | method          | code
    "/exact"      | "/exact"     | HttpMethod.GET  | 200
    // TODO(anuraaga): Seems to be an Armeria bug not to have :objectId here
    "/items/1234" | "/items/:"   | HttpMethod.POST | 200
    "/httperror"  | "/httperror" | HttpMethod.GET  | 501
    "/exception"  | "/exception" | HttpMethod.GET  | 500
  }
}
