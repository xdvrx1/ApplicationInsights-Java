/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.kafkaclients;

import static io.opentelemetry.api.trace.Span.Kind.PRODUCER;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.attributes.SemanticAttributes;
import io.opentelemetry.instrumentation.api.tracer.BaseTracer;
import org.apache.kafka.clients.ApiVersions;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.record.RecordBatch;

public class KafkaProducerTracer extends BaseTracer {
  private static final KafkaProducerTracer TRACER = new KafkaProducerTracer();

  public static KafkaProducerTracer tracer() {
    return TRACER;
  }

  public Span startProducerSpan(ProducerRecord<?, ?> record) {
    Span span = startSpan(spanNameOnProduce(record), PRODUCER);
    onProduce(span, record);
    return span;
  }

  // Do not inject headers for batch versions below 2
  // This is how similar check is being done in Kafka client itself:
  // https://github.com/apache/kafka/blob/05fcfde8f69b0349216553f711fdfc3f0259c601/clients/src/main/java/org/apache/kafka/common/record/MemoryRecordsBuilder.java#L411-L412
  // Also, do not inject headers if specified by JVM option or environment variable
  // This can help in mixed client environments where clients < 0.11 that do not support
  // headers attempt to read messages that were produced by clients > 0.11 and the magic
  // value of the broker(s) is >= 2
  public boolean shouldPropagate(ApiVersions apiVersions) {
    return apiVersions.maxUsableProduceMagic() >= RecordBatch.MAGIC_VALUE_V2
        && KafkaClientConfiguration.isPropagationEnabled();
  }

  public String spanNameOnProduce(ProducerRecord<?, ?> record) {
    return record.topic() + " send";
  }

  public void onProduce(Span span, ProducerRecord<?, ?> record) {
    span.setAttribute(SemanticAttributes.MESSAGING_SYSTEM, "kafka");
    span.setAttribute(SemanticAttributes.MESSAGING_DESTINATION_KIND, "topic");
    span.setAttribute(SemanticAttributes.MESSAGING_DESTINATION, record.topic());

    Integer partition = record.partition();
    if (partition != null) {
      span.setAttribute("partition", partition);
    }
    if (record.value() == null) {
      span.setAttribute("tombstone", true);
    }
  }

  @Override
  protected String getInstrumentationName() {
    return "io.opentelemetry.auto.kafka-clients-0.11";
  }
}
