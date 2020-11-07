/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.spring.autoconfigure.httpclients.webclient;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.instrumentation.spring.webflux.client.WebClientTracingFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;

@ExtendWith(MockitoExtension.class)
class WebClientBeanPostProcessorTest {

  @Mock Tracer tracer;

  WebClientBeanPostProcessor webClientBeanPostProcessor = new WebClientBeanPostProcessor(tracer);

  @Test
  @DisplayName(
      "when processed bean is NOT of type WebClient or WebClientBuilder should return Object")
  void returnsObject() {

    assertThat(
            webClientBeanPostProcessor.postProcessAfterInitialization(new Object(), "testObject"))
        .isExactlyInstanceOf(Object.class);
  }

  @Test
  @DisplayName("when processed bean is of type WebClient should return WebClient")
  void returnsWebClient() {
    assertThat(
            webClientBeanPostProcessor.postProcessAfterInitialization(
                WebClient.create(), "testWebClient"))
        .isInstanceOf(WebClient.class);
  }

  @Test
  @DisplayName("when processed bean is of type WebClientBuilder should return WebClientBuilder")
  void returnsWebClientBuilder() {
    assertThat(
            webClientBeanPostProcessor.postProcessAfterInitialization(
                WebClient.builder(), "testWebClientBuilder"))
        .isInstanceOf(WebClient.Builder.class);
  }

  @Test
  @DisplayName("when processed bean is of type WebClient should add exchange filter to WebClient")
  void addsExchangeFilterWebClient() {
    WebClient webClient = WebClient.create();
    Object processedWebClient =
        webClientBeanPostProcessor.postProcessAfterInitialization(webClient, "testWebClient");

    assertThat(processedWebClient).isInstanceOf(WebClient.class);
    ((WebClient) processedWebClient)
        .mutate()
        .filters(
            functions -> {
              assertThat(
                      functions.stream()
                          .filter(wctf -> wctf instanceof WebClientTracingFilter)
                          .count())
                  .isEqualTo(1);
            });
  }

  @Test
  @DisplayName(
      "when processed bean is of type WebClientBuilder should add ONE exchange filter to WebClientBuilder")
  void addsExchangeFilterWebClientBuilder() {

    WebClient.Builder webClientBuilder = WebClient.builder();
    webClientBeanPostProcessor.postProcessAfterInitialization(
        webClientBuilder, "testWebClientBuilder");
    webClientBeanPostProcessor.postProcessAfterInitialization(
        webClientBuilder, "testWebClientBuilder");
    webClientBeanPostProcessor.postProcessAfterInitialization(
        webClientBuilder, "testWebClientBuilder");

    webClientBuilder.filters(
        functions -> {
          assertThat(
                  functions.stream().filter(wctf -> wctf instanceof WebClientTracingFilter).count())
              .isEqualTo(1);
        });
  }
}
