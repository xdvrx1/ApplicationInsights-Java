/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.spring.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;

/** Spring Boot auto configuration test for {@link TracerAutoConfiguration} */
class TracerAutoConfigurationTest {
  @TestConfiguration
  static class CustomTracerConfiguration {
    @Bean
    public Tracer customTestTracer() {
      return OpenTelemetry.getGlobalTracer("customTestTracer");
    }
  }

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner();

  @Test
  @DisplayName("when Application Context contains Tracer bean should NOT initalize otelTracer")
  void customTracer() {
    this.contextRunner
        .withUserConfiguration(CustomTracerConfiguration.class)
        .withConfiguration(AutoConfigurations.of(TracerAutoConfiguration.class))
        .run(
            (context) -> {
              assertThat(context.containsBean("customTestTracer")).isTrue();
              assertThat(context.containsBean("otelTracer")).isFalse();
            });
  }

  @Test
  @DisplayName("when Application Context DOES NOT contain Tracer bean should initialize otelTracer")
  void initalizeTracer() {
    this.contextRunner
        .withConfiguration(AutoConfigurations.of(TracerAutoConfiguration.class))
        .run(
            (context) -> {
              assertThat(context.containsBean("otelTracer")).isTrue();
            });
  }

  @Test
  @DisplayName("when opentelemetry.trace.tracer.name is set should initialize tracer with name")
  void withTracerNameProperty() {
    this.contextRunner
        .withPropertyValues("opentelemetry.trace.tracer.name=testTracer")
        .withConfiguration(AutoConfigurations.of(TracerAutoConfiguration.class))
        .run(
            (context) -> {
              assertThat(context.getBean("otelTracer", Tracer.class))
                  .isEqualTo(OpenTelemetry.getGlobalTracer("testTracer"));
            });
  }
}
