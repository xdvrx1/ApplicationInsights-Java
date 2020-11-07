/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

import application.io.opentelemetry.sdk.OpenTelemetrySdk
import application.io.opentelemetry.sdk.trace.TracerSdkProvider
import io.opentelemetry.instrumentation.test.AgentTestRunner

class TracerSdkTest extends AgentTestRunner {

  def "direct access to sdk should not fail"() {
    when:
    def provider = OpenTelemetrySdk.getGlobalTracerManagement()

    then:
    provider instanceof TracerSdkProvider
  }
}
