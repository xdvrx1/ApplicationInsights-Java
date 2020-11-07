/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.tooling;

import com.google.common.collect.ImmutableMap;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.propagation.HttpTraceContext;
import io.opentelemetry.context.propagation.DefaultContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.extension.trace.propagation.AwsXRayPropagator;
import io.opentelemetry.extension.trace.propagation.B3Propagator;
import io.opentelemetry.extension.trace.propagation.JaegerPropagator;
import io.opentelemetry.extension.trace.propagation.OtTracerPropagator;
import io.opentelemetry.extension.trace.propagation.TraceMultiPropagator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PropagatorsInitializer {

  private static final Logger log = LoggerFactory.getLogger(PropagatorsInitializer.class);

  private static final String TRACE_CONTEXT = "tracecontext";
  private static final String B3 = "b3";
  private static final String B3_SINGLE = "b3single";
  private static final String JAEGER = "jaeger";
  private static final String OT_TRACER = "ottracer";
  private static final String XRAY = "xray";

  private static final Map<String, TextMapPropagator> TEXTMAP_PROPAGATORS =
      ImmutableMap.<String, TextMapPropagator>builder()
          .put(TRACE_CONTEXT, HttpTraceContext.getInstance())
          .put(B3, B3Propagator.getInstance())
          .put(JAEGER, JaegerPropagator.getInstance())
          .put(OT_TRACER, OtTracerPropagator.getInstance())
          .put(XRAY, AwsXRayPropagator.getInstance())
          .build();

  /** Initialize OpenTelemetry global Propagators with propagator list, if any. */
  public static void initializePropagators(List<String> propagators) {
    /* Only override the default propagators *if* the user specified any. */
    if (propagators.size() == 0) {
      // TODO this is probably temporary until default propagators are supplied by SDK
      //  https://github.com/open-telemetry/opentelemetry-java/issues/1742
      OpenTelemetry.setGlobalPropagators(
          DefaultContextPropagators.builder()
              .addTextMapPropagator(HttpTraceContext.getInstance())
              .build());
      return;
    }

    DefaultContextPropagators.Builder propagatorsBuilder = DefaultContextPropagators.builder();

    List<TextMapPropagator> textPropagators = new ArrayList<>(propagators.size());
    for (String propagatorId : propagators) {
      TextMapPropagator textPropagator = TEXTMAP_PROPAGATORS.get(propagatorId.trim().toLowerCase());
      if (textPropagator != null) {
        textPropagators.add(textPropagator);
        log.info("Added " + textPropagator + " propagator");
      } else {
        log.warn("No matching propagator for " + propagatorId);
      }
    }
    if (textPropagators.size() > 1) {
      TraceMultiPropagator.Builder traceMultiPropagatorBuilder = TraceMultiPropagator.builder();
      for (TextMapPropagator textPropagator : textPropagators) {
        traceMultiPropagatorBuilder.addPropagator(textPropagator);
      }
      propagatorsBuilder.addTextMapPropagator(traceMultiPropagatorBuilder.build());
    } else if (textPropagators.size() == 1) {
      propagatorsBuilder.addTextMapPropagator(textPropagators.get(0));
    }
    // Register it in the global propagators:
    OpenTelemetry.setGlobalPropagators(propagatorsBuilder.build());
  }
}
