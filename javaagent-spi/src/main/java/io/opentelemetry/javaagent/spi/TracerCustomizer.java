/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.spi;

import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.trace.TracerSdkManagement;
import io.opentelemetry.sdk.trace.TracerSdkProvider;
import io.opentelemetry.sdk.trace.config.TraceConfig;

/**
 * A service provider to allow programmatic customization of the tracing configuration. It will be
 * executed after the SDK has initialized a {@link TracerSdkProvider}. This means that not only can
 * the provided {@link TracerSdkProvider} be configured e.g. by calling {@link
 * TracerSdkProvider#updateActiveTraceConfig(TraceConfig)}, but static methods on {@link
 * io.opentelemetry.api.OpenTelemetry}, e.g., {@link
 * io.opentelemetry.api.OpenTelemetry#setPropagators(ContextPropagators)} can be used as well.
 *
 * <p>An implementation of {@link TracerCustomizer} can either be provided as part of an initializer
 * JAR, using the {@code otel.initializer.jar} property or can be included in the same JAR as the
 * agent in a redistribution.
 */
public interface TracerCustomizer {

  /** Callback executed after the initial {@link TracerSdkProvider} has been initialized. */
  void configure(TracerSdkManagement tracerManagement);
}
