/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.typed.server.http;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.javaagent.typed.server.ServerTypedSpan;

public abstract class HttpServerTypedSpan<T extends HttpServerTypedSpan, REQUEST, RESPONSE>
    extends ServerTypedSpan<T, REQUEST, RESPONSE> {

  public HttpServerTypedSpan(Span delegate) {
    super(delegate);
  }
}
