/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.spring.autoconfigure.exporters.zipkin;

import io.opentelemetry.exporter.zipkin.ZipkinSpanExporter;
import io.opentelemetry.instrumentation.spring.autoconfigure.TracerAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configures {@link ZipkinSpanExporter} for tracing.
 *
 * <p>Initializes {@link ZipkinSpanExporter} bean if bean is missing.
 */
@Configuration
@AutoConfigureBefore(TracerAutoConfiguration.class)
@EnableConfigurationProperties(ZipkinSpanExporterProperties.class)
@ConditionalOnProperty(
    prefix = "opentelemetry.trace.exporter.zipkin",
    name = "enabled",
    matchIfMissing = true)
@ConditionalOnClass(ZipkinSpanExporter.class)
public class ZipkinSpanExporterAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public ZipkinSpanExporter otelZipkinSpanExporter(
      ZipkinSpanExporterProperties zipkinSpanExporterProperties) {

    return ZipkinSpanExporter.builder()
        .setServiceName(zipkinSpanExporterProperties.getServiceName())
        .setEndpoint(zipkinSpanExporterProperties.getEndpoint())
        .build();
  }
}
