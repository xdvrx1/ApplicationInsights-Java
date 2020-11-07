/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */


import static io.opentelemetry.api.trace.Span.Kind.CLIENT
import static io.opentelemetry.instrumentation.test.server.http.TestHttpServer.httpServer
import static io.opentelemetry.instrumentation.test.utils.TraceUtils.basicSpan
import static io.opentelemetry.instrumentation.test.utils.TraceUtils.runUnderTrace

import io.opentelemetry.api.trace.attributes.SemanticAttributes
import io.opentelemetry.instrumentation.api.aiappid.AiAppId
import io.opentelemetry.instrumentation.test.AgentTestRunner
import io.opentelemetry.instrumentation.test.asserts.TraceAssert
import io.opentelemetry.sdk.trace.data.SpanData
import reactor.netty.http.client.HttpClient
import reactor.netty.http.client.HttpClientResponse
import spock.lang.AutoCleanup
import spock.lang.Shared

class ReactorNettyTest extends AgentTestRunner {
  @AutoCleanup
  @Shared
  def server = httpServer {
    handlers {
      prefix("success") {
        response.status(200).send("Hello.")
      }
    }
  }

  private int doRequest() {
    HttpClientResponse resp = HttpClient.create()
      .baseUrl(server.address.toString())
      .get()
      .uri("/success")
      .response()
      .block()
    return resp.status().code()
  }

  def "two basic GET requests #url"() {
    when:
    runUnderTrace("parent") {
      doRequest()
    }
    runUnderTrace("parent") {
      doRequest()
    }

    then:
    assertTraces(2) {
      trace(0, 2) {
        basicSpan(it, 0, "parent")
        clientSpan(it, 1, span(0))
      }
      trace(1, 2) {
        basicSpan(it, 0, "parent")
        clientSpan(it, 1, span(0))
      }
    }
  }

  void clientSpan(TraceAssert trace, int index, Object parentSpan, String method = "GET", URI uri = server.address.resolve("/success"), Integer status = 200) {
    def userAgent = "ReactorNetty/"
    trace.span(index) {
      if (parentSpan == null) {
        hasNoParent()
      } else {
        childOf((SpanData) parentSpan)
      }
      name "HTTP GET"
      kind CLIENT
      errored false
      attributes {
        "${SemanticAttributes.NET_TRANSPORT.key()}" "IP.TCP"
        "${SemanticAttributes.NET_PEER_NAME.key()}" uri.host
        "${SemanticAttributes.NET_PEER_IP.key()}" { it == null || it == "127.0.0.1" } // Optional
        "${SemanticAttributes.NET_PEER_PORT.key()}" uri.port > 0 ? uri.port : { it == null || it == 443 }
        "${SemanticAttributes.HTTP_URL.key()}" uri.toString()
        "${SemanticAttributes.HTTP_METHOD.key()}" method
        "${SemanticAttributes.HTTP_USER_AGENT.key()}" { it.startsWith(userAgent) }
        "${SemanticAttributes.HTTP_FLAVOR.key()}" "1.1"
        "${SemanticAttributes.HTTP_STATUS_CODE.key()}" status
        "$AiAppId.SPAN_TARGET_ATTRIBUTE_NAME" AiAppId.getAppId()
      }
    }
  }

}
