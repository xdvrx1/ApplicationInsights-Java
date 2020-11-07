/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.typed.client;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.javaagent.typed.client.http.HttpClientTypedSpan;

public class SampleHttpClientTypedSpan
    extends HttpClientTypedSpan<SampleHttpClientTypedSpan, String, String> {
  public SampleHttpClientTypedSpan(Span delegate) {
    super(delegate);
  }

  @Override
  protected SampleHttpClientTypedSpan onRequest(String o) {
    delegate.setAttribute("requested", true);
    return this;
  }

  @Override
  protected SampleHttpClientTypedSpan onResponse(String o) {
    delegate.setStatus(StatusCode.OK);
    return this;
  }

  @Override
  protected SampleHttpClientTypedSpan self() {
    return this;
  }
}
