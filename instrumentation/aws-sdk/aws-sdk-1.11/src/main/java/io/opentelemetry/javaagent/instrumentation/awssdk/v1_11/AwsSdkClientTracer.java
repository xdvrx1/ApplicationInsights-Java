/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.awssdk.v1_11;

import com.amazonaws.AmazonWebServiceRequest;
import com.amazonaws.AmazonWebServiceResponse;
import com.amazonaws.Request;
import com.amazonaws.Response;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapPropagator.Setter;
import io.opentelemetry.instrumentation.api.tracer.HttpClientTracer;
import java.net.URI;
import java.util.concurrent.ConcurrentHashMap;

public class AwsSdkClientTracer extends HttpClientTracer<Request<?>, Request<?>, Response<?>> {

  static final String COMPONENT_NAME = "java-aws-sdk";

  private static final AwsSdkClientTracer TRACER = new AwsSdkClientTracer();

  public static AwsSdkClientTracer tracer() {
    return TRACER;
  }

  private final NamesCache namesCache = new NamesCache();

  public AwsSdkClientTracer() {}

  @Override
  public String spanNameForRequest(Request<?> request) {
    if (request == null) {
      return DEFAULT_SPAN_NAME;
    }
    String awsServiceName = request.getServiceName();
    Class<?> awsOperation = request.getOriginalRequest().getClass();
    return qualifiedOperation(awsServiceName, awsOperation);
  }

  public Span startSpan(Request<?> request, RequestMeta requestMeta) {
    Span span = super.startSpan(request);

    String awsServiceName = request.getServiceName();
    AmazonWebServiceRequest originalRequest = request.getOriginalRequest();
    Class<?> awsOperation = originalRequest.getClass();

    span.setAttribute("aws.agent", COMPONENT_NAME);
    span.setAttribute("aws.service", awsServiceName);
    span.setAttribute("aws.operation", awsOperation.getSimpleName());
    span.setAttribute("aws.endpoint", request.getEndpoint().toString());

    if (requestMeta != null) {
      span.setAttribute("aws.bucket.name", requestMeta.getBucketName());
      span.setAttribute("aws.queue.url", requestMeta.getQueueUrl());
      span.setAttribute("aws.queue.name", requestMeta.getQueueName());
      span.setAttribute("aws.stream.name", requestMeta.getStreamName());
      span.setAttribute("aws.table.name", requestMeta.getTableName());
    }
    return span;
  }

  /**
   * Override startScope not to inject context into the request since no need to propagate context
   * to AWS backend services.
   */
  @Override
  public Scope startScope(Span span, Request<?> request) {
    return Context.current().with(span).with(CONTEXT_CLIENT_SPAN_KEY, span).makeCurrent();
  }

  @Override
  public Span onResponse(Span span, Response<?> response) {
    if (response != null && response.getAwsResponse() instanceof AmazonWebServiceResponse) {
      AmazonWebServiceResponse awsResp = (AmazonWebServiceResponse) response.getAwsResponse();
      span.setAttribute("aws.requestId", awsResp.getRequestId());
    }
    return super.onResponse(span, response);
  }

  private String qualifiedOperation(String service, Class<?> operation) {
    ConcurrentHashMap<String, String> cache = namesCache.get(operation);
    String qualified = cache.get(service);
    if (qualified == null) {
      qualified =
          service.replace("Amazon", "").trim()
              + '.'
              + operation.getSimpleName().replace("Request", "");
      cache.put(service, qualified);
    }
    return qualified;
  }

  @Override
  protected String method(Request<?> request) {
    return request.getHttpMethod().name();
  }

  @Override
  protected URI url(Request<?> request) {
    return request.getEndpoint();
  }

  @Override
  protected Integer status(Response<?> response) {
    return response.getHttpResponse().getStatusCode();
  }

  @Override
  protected String requestHeader(Request<?> request, String name) {
    return request.getHeaders().get(name);
  }

  @Override
  protected String responseHeader(Response<?> response, String name) {
    return response.getHttpResponse().getHeaders().get(name);
  }

  @Override
  protected Setter<Request<?>> getSetter() {
    return null;
  }

  @Override
  protected String getInstrumentationName() {
    return "io.opentelemetry.auto.aws-sdk";
  }

  static final class NamesCache extends ClassValue<ConcurrentHashMap<String, String>> {
    @Override
    protected ConcurrentHashMap<String, String> computeValue(Class<?> type) {
      return new ConcurrentHashMap<>();
    }
  }
}
