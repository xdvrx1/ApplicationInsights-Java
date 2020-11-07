/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.opentelemetryapi.metrics;

import application.io.opentelemetry.api.common.Labels;
import application.io.opentelemetry.api.metrics.LongValueObserver;
import io.opentelemetry.javaagent.instrumentation.opentelemetryapi.LabelBridging;

class ApplicationLongValueObserver implements LongValueObserver {

  private final io.opentelemetry.api.metrics.LongValueObserver agentLongValueObserver;

  public ApplicationLongValueObserver(
      io.opentelemetry.api.metrics.LongValueObserver agentLongValueObserver) {
    this.agentLongValueObserver = agentLongValueObserver;
  }

  @Override
  public void setCallback(Callback<LongResult> metricUpdater) {
    agentLongValueObserver.setCallback(new AgentResultLongValueObserver(metricUpdater));
  }

  public static class AgentResultLongValueObserver
      implements io.opentelemetry.api.metrics.AsynchronousInstrument.Callback<
          io.opentelemetry.api.metrics.LongValueObserver.LongResult> {

    private final Callback<LongResult> metricUpdater;

    public AgentResultLongValueObserver(Callback<LongResult> metricUpdater) {
      this.metricUpdater = metricUpdater;
    }

    @Override
    public void update(io.opentelemetry.api.metrics.LongValueObserver.LongResult result) {
      metricUpdater.update(new ApplicationResultLongValueObserver(result));
    }
  }

  public static class ApplicationResultLongValueObserver implements LongResult {

    private final io.opentelemetry.api.metrics.LongValueObserver.LongResult
        agentResultLongValueObserver;

    public ApplicationResultLongValueObserver(
        io.opentelemetry.api.metrics.LongValueObserver.LongResult agentResultLongValueObserver) {
      this.agentResultLongValueObserver = agentResultLongValueObserver;
    }

    @Override
    public void observe(long value, Labels labels) {
      agentResultLongValueObserver.observe(value, LabelBridging.toAgent(labels));
    }
  }

  static class Builder implements LongValueObserver.Builder {

    private final io.opentelemetry.api.metrics.LongValueObserver.Builder agentBuilder;

    public Builder(io.opentelemetry.api.metrics.LongValueObserver.Builder agentBuilder) {
      this.agentBuilder = agentBuilder;
    }

    @Override
    public LongValueObserver.Builder setDescription(String description) {
      agentBuilder.setDescription(description);
      return this;
    }

    @Override
    public LongValueObserver.Builder setUnit(String unit) {
      agentBuilder.setUnit(unit);
      return this;
    }

    @Override
    public LongValueObserver build() {
      return new ApplicationLongValueObserver(agentBuilder.build());
    }
  }
}
