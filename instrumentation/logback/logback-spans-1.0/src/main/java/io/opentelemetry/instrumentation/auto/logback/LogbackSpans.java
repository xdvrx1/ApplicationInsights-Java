/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.auto.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.instrumentation.api.config.Config;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LogbackSpans {

  private static final Logger log = LoggerFactory.getLogger(LogbackSpans.class);

  private static final Tracer TRACER =
      OpenTelemetry.getGlobalTracer("io.opentelemetry.auto.logback-1.0");

  public static void capture(final ILoggingEvent event) {

    Level level = event.getLevel();
    if (level.toInt() < getThreshold().toInt()) {
      // this needs to be configurable
      return;
    }

    Object throwableProxy = event.getThrowableProxy();
    Throwable t = null;
    if (throwableProxy instanceof ThrowableProxy) {
      // there is only one other subclass of ch.qos.logback.classic.spi.IThrowableProxy
      // and it is only used for logging exceptions over the wire
      t = ((ThrowableProxy) throwableProxy).getThrowable();
    }

    Span.Builder builder =
        TRACER
            .spanBuilder(event.getFormattedMessage())
            .setAttribute("ai.internal.log", true)
            .setAttribute("level", level.toString())
            .setAttribute("loggerName", event.getLoggerName());
    for (Map.Entry<String, String> entry : event.getMDCPropertyMap().entrySet()) {
      builder.setAttribute(entry.getKey(), entry.getValue());
    }
    Span span = builder.startSpan();
    if (t != null) {
      span.setAttribute("error.stack", toString(t));
    }
    span.end();
  }

  private static String toString(final Throwable t) {
    StringWriter out = new StringWriter();
    t.printStackTrace(new PrintWriter(out));
    return out.toString();
  }

  private static Level getThreshold() {
    String level = Config.get().getProperty("experimental.log.capture.threshold");
    if (level == null) {
      return Level.OFF;
    }
    switch (level.toUpperCase()) {
      case "OFF":
        return Level.OFF;
      case "FATAL":
      case "ERROR":
      case "SEVERE":
        return Level.ERROR;
      case "WARN":
      case "WARNING":
        return Level.WARN;
      case "INFO":
        return Level.INFO;
      case "CONFIG":
      case "DEBUG":
      case "FINE":
      case "FINER":
        return Level.DEBUG;
      case "TRACE":
      case "FINEST":
        return Level.TRACE;
      case "ALL":
        return Level.ALL;
      default:
        log.error("unexpected value for experimental.log.capture.threshold: {}", level);
        return Level.OFF;
    }
  }
}
