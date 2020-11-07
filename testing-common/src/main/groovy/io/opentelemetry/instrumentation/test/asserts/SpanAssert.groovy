/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.test.asserts

import static AttributesAssert.assertAttributes
import static io.opentelemetry.instrumentation.test.asserts.EventAssert.assertEvent

import groovy.transform.stc.ClosureParams
import groovy.transform.stc.SimpleType
import io.opentelemetry.api.common.AttributeConsumer
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.ReadableAttributes
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanId
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.attributes.SemanticAttributes
import java.util.regex.Pattern

class SpanAssert {
  private final SpanData span
  private final checked = [:]

  private final Set<Integer> assertedEventIndexes = new HashSet<>()

  private SpanAssert(span) {
    this.span = span
  }

  static void assertSpan(SpanData span,
                         @ClosureParams(value = SimpleType, options = ['io.opentelemetry.instrumentation.test.asserts.SpanAssert'])
                         @DelegatesTo(value = SpanAssert, strategy = Closure.DELEGATE_FIRST) Closure spec) {
    def asserter = new SpanAssert(span)
    asserter.assertSpan spec
    asserter.assertEventsAllVerified()
  }

  void assertSpan(
    @ClosureParams(value = SimpleType, options = ['io.opentelemetry.instrumentation.test.asserts.SpanAssert'])
    @DelegatesTo(value = SpanAssert, strategy = Closure.DELEGATE_FIRST) Closure spec) {
    def clone = (Closure) spec.clone()
    clone.delegate = this
    clone.resolveStrategy = Closure.DELEGATE_FIRST
    clone(this)
    assertDefaults()
  }

  void event(int index, @ClosureParams(value = SimpleType, options = ['io.opentelemetry.instrumentation.test.asserts.EventAssert']) @DelegatesTo(value = EventAssert, strategy = Closure.DELEGATE_FIRST) Closure spec) {
    if (index >= span.events.size()) {
      throw new ArrayIndexOutOfBoundsException(index)
    }
    assertedEventIndexes.add(index)
    assertEvent(span.events.get(index), spec)
  }

  def assertNameContains(String spanName, String... shouldContainArr) {
    for (String shouldContain : shouldContainArr) {
      assert spanName.contains(shouldContain)
    }
  }

  def name(String name) {
    assert span.name == name
    checked.name = true
  }

  def name(Pattern pattern) {
    assert span.name =~ pattern
    checked.name = true
  }

  def name(Closure spec) {
    assert ((Closure) spec).call(span.name)
    checked.name = true
  }

  def nameContains(String... nameParts) {
    assertNameContains(span.name, nameParts)
    checked.name = true
  }

  def kind(Span.Kind kind) {
    assert span.kind == kind
    checked.kind = true
  }

  def hasNoParent() {
    assert !SpanId.isValid(span.parentSpanId)
    checked.parentSpanId = true
  }

  def parentSpanId(String parentSpanId) {
    assert span.parentSpanId == parentSpanId
    checked.parentId = true
  }

  def traceId(String traceId) {
    assert span.traceId == traceId
    checked.traceId = true
  }

  def childOf(SpanData parent) {
    parentSpanId(parent.spanId)
    traceId(parent.traceId)
  }

  def hasLink(SpanData linked) {
    hasLink(linked.traceId, linked.spanId)
  }

  def hasLink(String traceId, String spanId) {
    def found = false
    for (def link : span.links) {
      if (link.context.traceIdAsHexString == traceId && link.context.spanIdAsHexString == spanId) {
        found = true
        break
      }
    }
    assert found
  }

  def status(StatusCode status) {
    assert span.status.canonicalCode == status
    checked.status = true
  }

  def errored(boolean errored) {
    if (errored) {
      // comparing only canonical code, since description may be different
      assert span.status.canonicalCode == StatusCode.ERROR
    } else {
      assert span.status.canonicalCode == StatusCode.UNSET
    }
    checked.status = true
  }

  def errorEvent(Class<Throwable> errorType) {
    errorEvent(errorType, null)
  }

  def errorEvent(Class<Throwable> errorType, message) {
    errorEvent(errorType, message, 0)
  }

  def errorEvent(Class<Throwable> errorType, message, int index) {
    event(index) {
      eventName(SemanticAttributes.EXCEPTION_EVENT_NAME)
      attributes {
        "${SemanticAttributes.EXCEPTION_TYPE.key()}" errorType.canonicalName
        "${SemanticAttributes.EXCEPTION_STACKTRACE.key()}" String
        if (message != null) {
          "${SemanticAttributes.EXCEPTION_MESSAGE.key()}" message
        }
      }
    }
  }

  void assertDefaults() {
    if (!checked.status) {
      errored(false)
    }
  }

  void attributes(@ClosureParams(value = SimpleType, options = ['io.opentelemetry.instrumentation.test.asserts.AttributesAssert'])
                  @DelegatesTo(value = AttributesAssert, strategy = Closure.DELEGATE_FIRST) Closure spec) {
    assertAttributes(toMap(span.attributes), spec)
  }

  void assertEventsAllVerified() {
    assert assertedEventIndexes.size() == span.events.size()
  }

  private Map<String, Object> toMap(ReadableAttributes attributes) {
    def map = new HashMap()
    attributes.forEach(new AttributeConsumer() {
      @Override
      <T> void accept(AttributeKey<T> key, T value) {
        map.put(key.key, value)
      }
    })
    return map
  }
}
