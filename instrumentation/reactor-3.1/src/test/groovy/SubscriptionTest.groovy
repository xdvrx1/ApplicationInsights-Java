/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

import static io.opentelemetry.instrumentation.test.utils.TraceUtils.basicSpan
import static io.opentelemetry.instrumentation.test.utils.TraceUtils.runUnderTrace

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.instrumentation.test.AgentTestRunner
import java.util.concurrent.CountDownLatch
import reactor.core.publisher.Mono

class SubscriptionTest extends AgentTestRunner {

  def "subscription test"() {
    when:
    CountDownLatch latch = new CountDownLatch(1)
    runUnderTrace("parent") {
      Mono<Connection> connection = Mono.create {
        it.success(new Connection())
      }
      connection.subscribe {
        it.query()
        latch.countDown()
      }
    }
    latch.await()

    then:
    assertTraces(1) {
      trace(0, 2) {
        basicSpan(it, 0, "parent")
        basicSpan(it, 1, "Connection.query", span(0))
      }
    }

  }

  static class Connection {
    static int query() {
      def span = OpenTelemetry.getGlobalTracer("test").spanBuilder("Connection.query").startSpan()
      span.end()
      return new Random().nextInt()
    }
  }
}
