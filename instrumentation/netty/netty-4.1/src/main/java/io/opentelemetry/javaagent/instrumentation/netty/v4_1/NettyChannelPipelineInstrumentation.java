/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.netty.v4_1;

import static io.opentelemetry.javaagent.tooling.ClassLoaderMatcher.hasClassesNamed;
import static io.opentelemetry.javaagent.tooling.bytebuddy.matcher.AgentElementMatchers.implementsInterface;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpRequestEncoder;
import io.netty.handler.codec.http.HttpResponseDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.util.Attribute;
import io.opentelemetry.context.Context;
import io.opentelemetry.javaagent.instrumentation.api.CallDepthThreadLocalMap;
import io.opentelemetry.javaagent.instrumentation.api.Java8BytecodeBridge;
import io.opentelemetry.javaagent.instrumentation.netty.v4_1.client.HttpClientRequestTracingHandler;
import io.opentelemetry.javaagent.instrumentation.netty.v4_1.client.HttpClientResponseTracingHandler;
import io.opentelemetry.javaagent.instrumentation.netty.v4_1.client.HttpClientTracingHandler;
import io.opentelemetry.javaagent.instrumentation.netty.v4_1.server.HttpServerRequestTracingHandler;
import io.opentelemetry.javaagent.instrumentation.netty.v4_1.server.HttpServerResponseTracingHandler;
import io.opentelemetry.javaagent.instrumentation.netty.v4_1.server.HttpServerTracingHandler;
import io.opentelemetry.javaagent.tooling.Instrumenter;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class NettyChannelPipelineInstrumentation extends Instrumenter.Default {

  static final String INSTRUMENTATION_NAME = "netty";
  static final String[] ADDITIONAL_INSTRUMENTATION_NAMES = {"netty-4.1"};

  public NettyChannelPipelineInstrumentation() {
    super(INSTRUMENTATION_NAME, ADDITIONAL_INSTRUMENTATION_NAMES);
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    // Optimization for expensive typeMatcher.
    return hasClassesNamed("io.netty.channel.ChannelPipeline");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return implementsInterface(named("io.netty.channel.ChannelPipeline"));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".AttributeKeys",
      packageName + ".AttributeKeys$1",
      // client helpers
      packageName + ".client.NettyHttpClientTracer",
      packageName + ".client.NettyResponseInjectAdapter",
      packageName + ".client.HttpClientRequestTracingHandler",
      packageName + ".client.HttpClientResponseTracingHandler",
      packageName + ".client.HttpClientTracingHandler",
      // server helpers
      packageName + ".server.NettyHttpServerTracer",
      packageName + ".server.NettyRequestExtractAdapter",
      packageName + ".server.HttpServerRequestTracingHandler",
      packageName + ".server.HttpServerResponseTracingHandler",
      packageName + ".server.HttpServerTracingHandler"
    };
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    Map<ElementMatcher<? super MethodDescription>, String> transformers = new HashMap<>();
    transformers.put(
        isMethod()
            .and(nameStartsWith("add"))
            .and(takesArgument(2, named("io.netty.channel.ChannelHandler"))),
        NettyChannelPipelineInstrumentation.class.getName() + "$ChannelPipelineAddAdvice");
    transformers.put(
        isMethod().and(named("connect")).and(returns(named("io.netty.channel.ChannelFuture"))),
        NettyChannelPipelineInstrumentation.class.getName() + "$ChannelPipelineConnectAdvice");
    return transformers;
  }

  /**
   * When certain handlers are added to the pipeline, we want to add our corresponding tracing
   * handlers. If those handlers are later removed, we may want to remove our handlers. That is not
   * currently implemented.
   */
  public static class ChannelPipelineAddAdvice {
    @Advice.OnMethodEnter
    public static int trackCallDepth(@Advice.Argument(2) ChannelHandler handler) {
      // Previously we used one unique call depth tracker for all handlers, using
      // ChannelPipeline.class as a key.
      // The problem with this approach is that it does not work with netty's
      // io.netty.channel.ChannelInitializer which provides an `initChannel` that can be used to
      // `addLast` other handlers. In that case the depth would exceed 0 and handlers added from
      // initializers would not be considered.
      // Using the specific handler key instead of the generic ChannelPipeline.class will help us
      // both to handle such cases and avoid adding our additional handlers in case of internal
      // calls of `addLast` to other method overloads with a compatible signature.
      return CallDepthThreadLocalMap.incrementCallDepth(handler.getClass());
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void addHandler(
        @Advice.Enter int callDepth,
        @Advice.This ChannelPipeline pipeline,
        @Advice.Argument(2) ChannelHandler handler) {
      if (callDepth > 0) {
        return;
      }
      CallDepthThreadLocalMap.reset(handler.getClass());

      try {
        // Server pipeline handlers
        if (handler instanceof HttpServerCodec) {
          pipeline.addLast(
              HttpServerTracingHandler.class.getName(), new HttpServerTracingHandler());
        } else if (handler instanceof HttpRequestDecoder) {
          pipeline.addLast(
              HttpServerRequestTracingHandler.class.getName(),
              new HttpServerRequestTracingHandler());
        } else if (handler instanceof HttpResponseEncoder) {
          pipeline.addLast(
              HttpServerResponseTracingHandler.class.getName(),
              new HttpServerResponseTracingHandler());
        } else
        // Client pipeline handlers
        if (handler instanceof HttpClientCodec) {
          pipeline.addLast(
              HttpClientTracingHandler.class.getName(), new HttpClientTracingHandler());
        } else if (handler instanceof HttpRequestEncoder) {
          pipeline.addLast(
              HttpClientRequestTracingHandler.class.getName(),
              new HttpClientRequestTracingHandler());
        } else if (handler instanceof HttpResponseDecoder) {
          pipeline.addLast(
              HttpClientResponseTracingHandler.class.getName(),
              new HttpClientResponseTracingHandler());
        }
      } catch (IllegalArgumentException e) {
        // Prevented adding duplicate handlers.
      }
    }
  }

  public static class ChannelPipelineConnectAdvice {
    @Advice.OnMethodEnter
    public static void addParentSpan(@Advice.This ChannelPipeline pipeline) {
      Attribute<Context> attribute =
          pipeline.channel().attr(AttributeKeys.PARENT_CONNECT_CONTEXT_ATTRIBUTE_KEY);
      attribute.compareAndSet(null, Java8BytecodeBridge.currentContext());
    }
  }
}
