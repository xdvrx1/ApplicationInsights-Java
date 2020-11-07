/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

import static io.opentelemetry.instrumentation.test.utils.TraceUtils.runUnderTrace
import static io.opentelemetry.api.trace.Span.Kind.CLIENT
import static io.opentelemetry.api.trace.Span.Kind.INTERNAL

import io.opentelemetry.instrumentation.test.AgentTestRunner
import io.opentelemetry.api.trace.attributes.SemanticAttributes
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import spring.jpa.JpaCustomer
import spring.jpa.JpaCustomerRepository
import spring.jpa.JpaPersistenceConfig

class SpringJpaTest extends AgentTestRunner {
  def "test object method"() {
    setup:
    def context = new AnnotationConfigApplicationContext(JpaPersistenceConfig)
    def repo = context.getBean(JpaCustomerRepository)

    // when Spring JPA sets up, it issues metadata queries -- clear those traces
    TEST_WRITER.clear()

    when:
    runUnderTrace("toString test") {
      repo.toString()
    }

    then:
    // Asserting that a span is NOT created for toString
    assertTraces(1) {
      trace(0, 1) {
        span(0) {
          name "toString test"
          attributes {
          }
        }
      }
    }
  }

  def "test CRUD"() {
    // moved inside test -- otherwise, miss the opportunity to instrument
    def context = new AnnotationConfigApplicationContext(JpaPersistenceConfig)
    def repo = context.getBean(JpaCustomerRepository)

    // when Spring JPA sets up, it issues metadata queries -- clear those traces
    TEST_WRITER.clear()

    setup:
    def customer = new JpaCustomer("Bob", "Anonymous")

    expect:
    customer.id == null
    !repo.findAll().iterator().hasNext() // select

    assertTraces(1) {
      trace(0, 2) {
        span(0) {
          name "JpaRepository.findAll"
          kind INTERNAL
          errored false
          attributes {
          }
        }
        span(1) { // select
          name ~/^select /
          kind CLIENT
          childOf span(0)
          attributes {
            "${SemanticAttributes.DB_SYSTEM.key()}" "hsqldb"
            "${SemanticAttributes.DB_NAME.key()}" "test"
            "${SemanticAttributes.DB_USER.key()}" "sa"
            "${SemanticAttributes.DB_STATEMENT.key()}" ~/^select /
            "${SemanticAttributes.DB_CONNECTION_STRING.key()}" "hsqldb:mem:"
          }
        }
      }
    }
    TEST_WRITER.clear()

    when:
    repo.save(customer) // insert
    def savedId = customer.id

    then:
    customer.id != null
    assertTraces(1) {
      trace(0, 2) {
        span(0) {
          name "CrudRepository.save"
          kind INTERNAL
          errored false
          attributes {
          }
        }
        span(1) { // insert
          name ~/^insert /
          kind CLIENT
          childOf span(0)
          attributes {
            "${SemanticAttributes.DB_SYSTEM.key()}" "hsqldb"
            "${SemanticAttributes.DB_NAME.key()}" "test"
            "${SemanticAttributes.DB_USER.key()}" "sa"
            "${SemanticAttributes.DB_STATEMENT.key()}" ~/^insert /
            "${SemanticAttributes.DB_CONNECTION_STRING.key()}" "hsqldb:mem:"
          }
        }
      }
    }
    TEST_WRITER.clear()

    when:
    customer.firstName = "Bill"
    repo.save(customer)

    then:
    customer.id == savedId
    assertTraces(1) {
      trace(0, 3) {
        span(0) {
          name "CrudRepository.save"
          kind INTERNAL
          errored false
          attributes {
          }
        }
        span(1) { // select
          name ~/^select /
          kind CLIENT
          childOf span(0)
          attributes {
            "${SemanticAttributes.DB_SYSTEM.key()}" "hsqldb"
            "${SemanticAttributes.DB_NAME.key()}" "test"
            "${SemanticAttributes.DB_USER.key()}" "sa"
            "${SemanticAttributes.DB_STATEMENT.key()}" ~/^select /
            "${SemanticAttributes.DB_CONNECTION_STRING.key()}" "hsqldb:mem:"
          }
        }
        span(2) { // update
          name ~/^update /
          kind CLIENT
          childOf span(0)
          attributes {
            "${SemanticAttributes.DB_SYSTEM.key()}" "hsqldb"
            "${SemanticAttributes.DB_NAME.key()}" "test"
            "${SemanticAttributes.DB_USER.key()}" "sa"
            "${SemanticAttributes.DB_STATEMENT.key()}" ~/^update /
            "${SemanticAttributes.DB_CONNECTION_STRING.key()}" "hsqldb:mem:"
          }
        }
      }
    }
    TEST_WRITER.clear()

    when:
    customer = repo.findByLastName("Anonymous")[0] // select

    then:
    assertTraces(1) {
      trace(0, 2) {
        span(0) {
          name "JpaCustomerRepository.findByLastName"
          kind INTERNAL
          errored false
          attributes {
          }
        }
        span(1) { // select
          name ~/^select /
          kind CLIENT
          childOf span(0)
          attributes {
            "${SemanticAttributes.DB_SYSTEM.key()}" "hsqldb"
            "${SemanticAttributes.DB_NAME.key()}" "test"
            "${SemanticAttributes.DB_USER.key()}" "sa"
            "${SemanticAttributes.DB_STATEMENT.key()}" ~/^select /
            "${SemanticAttributes.DB_CONNECTION_STRING.key()}" "hsqldb:mem:"
          }
        }
      }
    }
    TEST_WRITER.clear()

    when:
    repo.delete(customer) // delete

    then:
    assertTraces(1) {
      trace(0, 3) {
        span(0) {
          name "CrudRepository.delete"
          kind INTERNAL
          errored false
          attributes {
          }
        }
        span(1) { // select
          name ~/^select /
          kind CLIENT
          childOf span(0)
          attributes {
            "${SemanticAttributes.DB_SYSTEM.key()}" "hsqldb"
            "${SemanticAttributes.DB_NAME.key()}" "test"
            "${SemanticAttributes.DB_USER.key()}" "sa"
            "${SemanticAttributes.DB_STATEMENT.key()}" ~/^select /
            "${SemanticAttributes.DB_CONNECTION_STRING.key()}" "hsqldb:mem:"
          }
        }
        span(2) { // delete
          name ~/^delete /
          kind CLIENT
          childOf span(0)
          attributes {
            "${SemanticAttributes.DB_SYSTEM.key()}" "hsqldb"
            "${SemanticAttributes.DB_NAME.key()}" "test"
            "${SemanticAttributes.DB_USER.key()}" "sa"
            "${SemanticAttributes.DB_STATEMENT.key()}" ~/^delete /
            "${SemanticAttributes.DB_CONNECTION_STRING.key()}" "hsqldb:mem:"
          }
        }
      }
    }
    TEST_WRITER.clear()
  }
}
