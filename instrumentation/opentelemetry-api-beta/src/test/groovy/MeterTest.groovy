/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

import static io.opentelemetry.sdk.metrics.data.MetricData.Type.GAUGE_DOUBLE
import static io.opentelemetry.sdk.metrics.data.MetricData.Type.GAUGE_LONG
import static io.opentelemetry.sdk.metrics.data.MetricData.Type.MONOTONIC_DOUBLE
import static io.opentelemetry.sdk.metrics.data.MetricData.Type.MONOTONIC_LONG
import static io.opentelemetry.sdk.metrics.data.MetricData.Type.NON_MONOTONIC_DOUBLE
import static io.opentelemetry.sdk.metrics.data.MetricData.Type.NON_MONOTONIC_LONG
import static io.opentelemetry.sdk.metrics.data.MetricData.Type.SUMMARY

import application.io.opentelemetry.api.OpenTelemetry
import application.io.opentelemetry.api.common.Labels
import application.io.opentelemetry.api.metrics.AsynchronousInstrument
import io.opentelemetry.instrumentation.test.AgentTestRunner
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.data.MetricData

class MeterTest extends AgentTestRunner {

  def "test counter #builderMethod bound=#bind"() {
    given:
    // meters are global, and no way to unregister them, so tests use random name to avoid each other
    def instrumentationName = "test" + new Random().nextLong()

    when:
    def meter = OpenTelemetry.getGlobalMeterProvider().get(instrumentationName, "1.2.3")
    def instrument = meter."$builderMethod"("test")
      .setDescription("d")
      .setUnit("u")
      .build()
    if (bind) {
      instrument = instrument.bind(Labels.empty())
    }
    if (bind) {
      instrument.add(value1)
      instrument.add(value2)
    } else {
      instrument.add(value1, Labels.of("q", "r"))
      instrument.add(value2, Labels.of("q", "r"))
    }

    then:
    def metricData = findMetric(OpenTelemetrySdk.getGlobalMeterProvider().getMetricProducer().collectAllMetrics(), instrumentationName, "test")
    metricData != null
    metricData.description == "d"
    metricData.unit == "u"
    metricData.type == expectedType
    metricData.instrumentationLibraryInfo.name == instrumentationName
    metricData.instrumentationLibraryInfo.version == "1.2.3"
    metricData.points.size() == 1
    def point = metricData.points.iterator().next()
    if (bind) {
      point.labels == io.opentelemetry.api.common.Labels.of("w", "x", "y", "z")
    } else {
      point.labels == io.opentelemetry.api.common.Labels.of("q", "r")
    }
    point.value == expectedValue

    where:
    builderMethod                | bind  | value1 | value2 | expectedValue | expectedType
    "longCounterBuilder"         | false | 5      | 6      | 11            | MONOTONIC_LONG
    "longCounterBuilder"         | true  | 5      | 6      | 11            | MONOTONIC_LONG
    "longUpDownCounterBuilder"   | false | 5      | 6      | 11            | NON_MONOTONIC_LONG
    "longUpDownCounterBuilder"   | true  | 5      | 6      | 11            | NON_MONOTONIC_LONG
    "doubleCounterBuilder"       | false | 5.5    | 6.6    | 12.1          | MONOTONIC_DOUBLE
    "doubleCounterBuilder"       | true  | 5.5    | 6.6    | 12.1          | MONOTONIC_DOUBLE
    "doubleUpDownCounterBuilder" | false | 5.5    | 6.6    | 12.1          | NON_MONOTONIC_DOUBLE
    "doubleUpDownCounterBuilder" | true  | 5.5    | 6.6    | 12.1          | NON_MONOTONIC_DOUBLE
  }

  def "test recorder #builderMethod bound=#bind"() {
    given:
    // meters are global, and no way to unregister them, so tests use random name to avoid each other
    def instrumentationName = "test" + new Random().nextLong()

    when:
    def meter = OpenTelemetry.getGlobalMeterProvider().get(instrumentationName, "1.2.3")
    def instrument = meter."$builderMethod"("test")
      .setDescription("d")
      .setUnit("u")
      .build()
    if (bind) {
      instrument = instrument.bind(Labels.empty())
    }
    if (bind) {
      instrument.record(value1)
      instrument.record(value2)
    } else {
      instrument.record(value1, Labels.of("q", "r"))
      instrument.record(value2, Labels.of("q", "r"))
    }

    then:
    def metricData = findMetric(OpenTelemetrySdk.getGlobalMeterProvider().getMetricProducer().collectAllMetrics(), instrumentationName, "test")
    metricData != null
    metricData.description == "d"
    metricData.unit == "u"
    metricData.type == SUMMARY
    metricData.instrumentationLibraryInfo.name == instrumentationName
    metricData.instrumentationLibraryInfo.version == "1.2.3"
    metricData.points.size() == 1
    def point = metricData.points.iterator().next()
    if (bind) {
      point.labels == io.opentelemetry.api.common.Labels.of("w", "x", "y", "z")
    } else {
      point.labels == io.opentelemetry.api.common.Labels.of("q", "r")
    }

    where:
    builderMethod                | bind  | value1 | value2 | sum
    "longValueRecorderBuilder"   | false | 5      | 6      | 11
    "longValueRecorderBuilder"   | true  | 5      | 6      | 11
    "doubleValueRecorderBuilder" | false | 5.5    | 6.6    | 12.1
    "doubleValueRecorderBuilder" | true  | 5.5    | 6.6    | 12.1
  }

  def "test observer #builderMethod"() {
    given:
    // meters are global, and no way to unregister them, so tests use random name to avoid each other
    def instrumentationName = "test" + new Random().nextLong()

    when:
    def meter = OpenTelemetry.getGlobalMeterProvider().get(instrumentationName, "1.2.3")
    def instrument = meter."$builderMethod"("test")
      .setDescription("d")
      .setUnit("u")
      .build()
    if (builderMethod == "longSumObserverBuilder") {
      instrument.setCallback(new AsynchronousInstrument.Callback<AsynchronousInstrument.LongResult>() {
        @Override
        void update(AsynchronousInstrument.LongResult resultLongSumObserver) {
          resultLongSumObserver.observe(123, Labels.of("q", "r"))
        }
      })
    } else if (builderMethod == "longUpDownSumObserverBuilder") {
      instrument.setCallback(new AsynchronousInstrument.Callback<AsynchronousInstrument.LongResult>() {
        @Override
        void update(AsynchronousInstrument.LongResult resultLongUpDownSumObserver) {
          resultLongUpDownSumObserver.observe(123, Labels.of("q", "r"))
        }
      })
    } else if (builderMethod == "longValueObserverBuilder") {
      instrument.setCallback(new AsynchronousInstrument.Callback<AsynchronousInstrument.LongResult>() {
        @Override
        void update(AsynchronousInstrument.LongResult resultLongObserver) {
          resultLongObserver.observe(123, Labels.of("q", "r"))
        }
      })
    } else if (builderMethod == "doubleSumObserverBuilder") {
      instrument.setCallback(new AsynchronousInstrument.Callback<AsynchronousInstrument.DoubleResult>() {
        @Override
        void update(AsynchronousInstrument.DoubleResult resultDoubleSumObserver) {
          resultDoubleSumObserver.observe(1.23, Labels.of("q", "r"))
        }
      })
    } else if (builderMethod == "doubleUpDownSumObserverBuilder") {
      instrument.setCallback(new AsynchronousInstrument.Callback<AsynchronousInstrument.DoubleResult>() {
        @Override
        void update(AsynchronousInstrument.DoubleResult resultDoubleUpDownSumObserver) {
          resultDoubleUpDownSumObserver.observe(1.23, Labels.of("q", "r"))
        }
      })
    } else if (builderMethod == "doubleValueObserverBuilder") {
      instrument.setCallback(new AsynchronousInstrument.Callback<AsynchronousInstrument.DoubleResult>() {
        @Override
        void update(AsynchronousInstrument.DoubleResult resultDoubleObserver) {
          resultDoubleObserver.observe(1.23, Labels.of("q", "r"))
        }
      })
    }

    then:
    def metricData = findMetric(OpenTelemetrySdk.getGlobalMeterProvider().getMetricProducer().collectAllMetrics(), instrumentationName, "test")
    metricData != null
    metricData.description == "d"
    metricData.unit == "u"
    metricData.type == expectedType
    metricData.instrumentationLibraryInfo.name == instrumentationName
    metricData.instrumentationLibraryInfo.version == "1.2.3"
    metricData.points.size() == 1
    def point = metricData.points.iterator().next()
    point.labels == io.opentelemetry.api.common.Labels.of("q", "r")
    if (builderMethod.startsWith("long")) {
      point.value == 123
    } else {
      point.value == 1.23
    }

    where:
    builderMethod                    | valueMethod | expectedType
    "longSumObserverBuilder"         | "value"     | MONOTONIC_LONG
    "longUpDownSumObserverBuilder"   | "value"     | NON_MONOTONIC_LONG
    "longValueObserverBuilder"       | "sum"       | GAUGE_LONG
    "doubleSumObserverBuilder"       | "value"     | MONOTONIC_DOUBLE
    "doubleUpDownSumObserverBuilder" | "value"     | NON_MONOTONIC_DOUBLE
    "doubleValueObserverBuilder"     | "sum"       | GAUGE_DOUBLE
  }

  def "test batch recorder"() {
    given:
    // meters are global, and no way to unregister them, so tests use random name to avoid each other
    def instrumentationName = "test" + new Random().nextLong()

    when:
    def meter = OpenTelemetry.getGlobalMeterProvider().get(instrumentationName, "1.2.3")
    def longCounter = meter.longCounterBuilder("test")
      .setDescription("d")
      .setUnit("u")
      .build()
    def doubleMeasure = meter.doubleValueRecorderBuilder("test2")
      .setDescription("d")
      .setUnit("u")
      .build()

    meter.newBatchRecorder("q", "r")
      .put(longCounter, 5)
      .put(longCounter, 6)
      .put(doubleMeasure, 5.5)
      .put(doubleMeasure, 6.6)
      .record()

    def allMetrics = OpenTelemetrySdk.getGlobalMeterProvider().getMetricProducer().collectAllMetrics()

    then:
    def metricData = findMetric(allMetrics, instrumentationName, "test")
    metricData != null
    metricData.description == "d"
    metricData.unit == "u"
    metricData.type == MONOTONIC_LONG
    metricData.instrumentationLibraryInfo.name == instrumentationName
    metricData.instrumentationLibraryInfo.version == "1.2.3"
    metricData.points.size() == 1
    def point = metricData.points.iterator().next()
    point.labels == io.opentelemetry.api.common.Labels.of("q", "r")
    point.value == 11

    def metricData2 = findMetric(allMetrics, instrumentationName, "test2")
    metricData2 != null
    metricData2.description == "d"
    metricData2.unit == "u"
    metricData2.type == SUMMARY
    metricData2.instrumentationLibraryInfo.name == instrumentationName
    metricData2.instrumentationLibraryInfo.version == "1.2.3"
    metricData2.points.size() == 1
    def point2 = metricData2.points.iterator().next()
    point2.labels == io.opentelemetry.api.common.Labels.of("q", "r")
    point2.count == 2
    point2.sum == 12.1
  }

  def findMetric(Collection<MetricData> allMetrics, instrumentationName, metricName) {
    for (def metric : allMetrics) {
      if (metric.instrumentationLibraryInfo.name == instrumentationName && metric.name == metricName) {
        return metric
      }
    }
  }
}
