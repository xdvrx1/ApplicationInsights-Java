/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.spring.autoconfigure.exporters.otlp;

import io.grpc.ManagedChannel;
import io.opentelemetry.exporter.otlp.OtlpGrpcSpanExporter;
import io.opentelemetry.instrumentation.spring.autoconfigure.TracerAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configures {@link OtlpGrpcSpanExporter} for tracing.
 *
 * <p>Initializes {@link OtlpGrpcSpanExporter} bean if bean is missing.
 */
@Configuration
@AutoConfigureBefore(TracerAutoConfiguration.class)
@EnableConfigurationProperties(OtlpGrpcSpanExporterProperties.class)
@ConditionalOnProperty(
    prefix = "opentelemetry.trace.exporter.otlp",
    name = "enabled",
    matchIfMissing = true)
@ConditionalOnClass({OtlpGrpcSpanExporter.class, ManagedChannel.class})
public class OtlpGrpcSpanExporterAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public OtlpGrpcSpanExporter otelOtlpGrpcSpanExporter(
      OtlpGrpcSpanExporterProperties otlpGrpcSpanExporterProperties) {

    return OtlpGrpcSpanExporter.builder()
        .setEndpoint(otlpGrpcSpanExporterProperties.getEndpoint())
        .setDeadlineMs(otlpGrpcSpanExporterProperties.getSpanTimeout().toMillis())
        .build();
  }
}
