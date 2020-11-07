/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

import static io.opentelemetry.instrumentation.test.base.HttpServerTest.ServerEndpoint.SUCCESS
import static io.opentelemetry.instrumentation.test.utils.TraceUtils.basicSpan
import static io.opentelemetry.api.trace.Span.Kind.CLIENT
import static io.opentelemetry.api.trace.Span.Kind.SERVER

import io.opentelemetry.instrumentation.test.AgentTestRunner
import io.opentelemetry.instrumentation.test.utils.OkHttpUtils
import io.opentelemetry.instrumentation.test.utils.PortUtils
import io.opentelemetry.api.trace.attributes.SemanticAttributes
import io.vertx.reactivex.core.Vertx
import okhttp3.OkHttpClient
import okhttp3.Request
import spock.lang.Shared

class VertxReactivePropagationTest extends AgentTestRunner {
  @Shared
  OkHttpClient client = OkHttpUtils.client()

  @Shared
  int port

  @Shared
  Vertx server

  def setupSpec() {
    port = PortUtils.randomOpenPort()
    server = VertxReactiveWebServer.start(port)
  }

  def cleanupSpec() {
    server.close()
  }

  //Verifies that context is correctly propagated and sql query span has correct parent.
  //Tests io.opentelemetry.javaagent.instrumentation.vertx.reactive.VertxRxInstrumentation
  def "should propagate context over vert.x rx-java framework"() {
    setup:
    def url = "http://localhost:$port/listProducts"
    def request = new Request.Builder().url(url).get().build()
    def response = client.newCall(request).execute()

    expect:
    response.code() == SUCCESS.status

    and:
    assertTraces(1) {
      trace(0, 4) {
        span(0) {
          name "/listProducts"
          kind SERVER
          errored false
          hasNoParent()
          attributes {
            "${SemanticAttributes.NET_PEER_PORT.key()}" Long
            "${SemanticAttributes.NET_PEER_IP.key()}" "127.0.0.1"
            "${SemanticAttributes.HTTP_URL.key()}" url
            "${SemanticAttributes.HTTP_METHOD.key()}" "GET"
            "${SemanticAttributes.HTTP_STATUS_CODE.key()}" 200
            "${SemanticAttributes.HTTP_FLAVOR.key()}" "HTTP/1.1"
            "${SemanticAttributes.HTTP_USER_AGENT.key()}" String
            "${SemanticAttributes.HTTP_CLIENT_IP.key()}" "127.0.0.1"
          }
        }
        basicSpan(it, 1, "handleListProducts", span(0))
        basicSpan(it, 2, "listProducts", span(1))
        span(3) {
          name "SELECT id, name, price, weight FROM products"
          kind CLIENT
          childOf span(2)
          errored false
          attributes {
            "${SemanticAttributes.DB_SYSTEM.key()}" "hsqldb"
            "${SemanticAttributes.DB_NAME.key()}" "test"
            "${SemanticAttributes.DB_USER.key()}" "SA"
            "${SemanticAttributes.DB_STATEMENT.key()}" "SELECT id, name, price, weight FROM products"
            "${SemanticAttributes.DB_CONNECTION_STRING.key()}" "hsqldb:mem:"
          }
        }
      }
    }
  }


}
