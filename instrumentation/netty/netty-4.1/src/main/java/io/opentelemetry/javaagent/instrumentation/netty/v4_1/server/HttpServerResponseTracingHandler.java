/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.netty.v4_1.server;

import static io.opentelemetry.javaagent.instrumentation.netty.v4_1.server.NettyHttpServerTracer.tracer;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpResponse;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.instrumentation.api.aiappid.AiAppId;

public class HttpServerResponseTracingHandler extends ChannelOutboundHandlerAdapter {

  @Override
  public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise prm) {
    Context context = tracer().getServerContext(ctx.channel());
    if (context == null || !(msg instanceof HttpResponse)) {
      ctx.write(msg, prm);
      return;
    }

    final String appId = AiAppId.getAppId();
    if (!appId.isEmpty()) {
      final HttpResponse response = (HttpResponse) msg;
      response.headers().set(AiAppId.RESPONSE_HEADER_NAME, "appId=" + appId);
    }

    Span span = Span.fromContext(context);
    try (Scope ignored = context.makeCurrent()) {
      ctx.write(msg, prm);
    } catch (Throwable throwable) {
      tracer().endExceptionally(span, throwable);
      throw throwable;
    }
    tracer().end(span, (HttpResponse) msg);
  }
}
