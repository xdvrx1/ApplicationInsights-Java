/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.test.log

import static io.opentelemetry.instrumentation.test.utils.TraceUtils.runUnderTrace

import io.opentelemetry.instrumentation.test.AgentTestRunner
import io.opentelemetry.instrumentation.test.utils.ConfigUtils
import spock.lang.Unroll

/**
 * This class represents the standard test cases that new logging library integrations MUST
 * satisfy in order to support log events.
 */
@Unroll
abstract class LogEventsTestBase extends AgentTestRunner {

  // TODO intentionally not resetting config because it causes the second test to fail in
  // java-util-logging-spans instrumentation
  static {
    ConfigUtils.updateConfig {
      it.setProperty("experimental.log.capture.threshold", "warn")
    }
  }

  abstract Object createLogger(String name)

  String warn() {
    return "warn"
  }

  String error() {
    return "error"
  }

  def "capture #testMethod (#capture)"() {
    setup:
    runUnderTrace("test") {
      def logger = createLogger("abc")
      logger."$testMethod"("xyz")
    }

    expect:
    assertTraces(1) {
      trace(0, capture ? 2 : 1) {
        span(0) {
          name "test"
        }
        if (capture) {
          span(1) {
            name "xyz"
            attributes {
              "ai.internal.log" true
              "level" testMethod.toUpperCase()
              "loggerName" "abc"
            }
          }
        }
      }
    }

    where:
    testMethod | capture
    "info"     | false
    warn()     | true
    error()    | true
  }

  def "capture #testMethod (#capture) as span when no current span"() {
    when:
    def logger = createLogger("abc")
    logger."$testMethod"("xyz")

    then:
    if (capture) {
      assertTraces(1) {
        trace(0, 1) {
          span(0) {
            name "xyz"
            attributes {
              "ai.internal.log" true
              "level" testMethod.toUpperCase()
              "loggerName" "abc"
            }
          }
        }
      }
    } else {
      Thread.sleep(500) // sleep a bit just to make sure no span is captured
      assertTraces(0) {
      }
    }

    where:
    testMethod | capture
    "info"     | false
    warn()     | true
    error()    | true
  }
}
