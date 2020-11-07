/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.opentelemetryapi.metrics;

import application.io.opentelemetry.api.common.Labels;
import application.io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.javaagent.instrumentation.opentelemetryapi.LabelBridging;

class ApplicationLongUpDownCounter implements LongUpDownCounter {

  private final io.opentelemetry.api.metrics.LongUpDownCounter agentLongUpDownCounter;

  ApplicationLongUpDownCounter(
      io.opentelemetry.api.metrics.LongUpDownCounter agentLongUpDownCounter) {
    this.agentLongUpDownCounter = agentLongUpDownCounter;
  }

  io.opentelemetry.api.metrics.LongUpDownCounter getAgentLongUpDownCounter() {
    return agentLongUpDownCounter;
  }

  @Override
  public void add(long delta, Labels labels) {
    agentLongUpDownCounter.add(delta, LabelBridging.toAgent(labels));
  }

  @Override
  public void add(long l) {
    agentLongUpDownCounter.add(l);
  }

  @Override
  public BoundLongUpDownCounter bind(Labels labels) {
    return new BoundInstrument(agentLongUpDownCounter.bind(LabelBridging.toAgent(labels)));
  }

  static class BoundInstrument implements BoundLongUpDownCounter {

    private final io.opentelemetry.api.metrics.LongUpDownCounter.BoundLongUpDownCounter
        agentBoundLongUpDownCounter;

    BoundInstrument(
        io.opentelemetry.api.metrics.LongUpDownCounter.BoundLongUpDownCounter
            agentBoundLongUpDownCounter) {
      this.agentBoundLongUpDownCounter = agentBoundLongUpDownCounter;
    }

    @Override
    public void add(long delta) {
      agentBoundLongUpDownCounter.add(delta);
    }

    @Override
    public void unbind() {
      agentBoundLongUpDownCounter.unbind();
    }
  }

  static class Builder implements LongUpDownCounter.Builder {

    private final io.opentelemetry.api.metrics.LongUpDownCounter.Builder agentBuilder;

    Builder(io.opentelemetry.api.metrics.LongUpDownCounter.Builder agentBuilder) {
      this.agentBuilder = agentBuilder;
    }

    @Override
    public LongUpDownCounter.Builder setDescription(String description) {
      agentBuilder.setDescription(description);
      return this;
    }

    @Override
    public LongUpDownCounter.Builder setUnit(String unit) {
      agentBuilder.setUnit(unit);
      return this;
    }

    @Override
    public LongUpDownCounter build() {
      return new ApplicationLongUpDownCounter(agentBuilder.build());
    }
  }
}
