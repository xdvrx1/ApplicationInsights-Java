/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

import static io.opentelemetry.api.trace.Span.Kind.CLIENT
import static io.opentelemetry.api.trace.Span.Kind.INTERNAL

import io.opentelemetry.api.trace.attributes.SemanticAttributes
import org.hibernate.Criteria
import org.hibernate.Session
import org.hibernate.criterion.Order
import org.hibernate.criterion.Restrictions

class CriteriaTest extends AbstractHibernateTest {

  def "test criteria.#methodName"() {
    setup:
    Session session = sessionFactory.openSession()
    session.beginTransaction()
    Criteria criteria = session.createCriteria(Value)
      .add(Restrictions.like("name", "Hello"))
      .addOrder(Order.desc("name"))
    interaction.call(criteria)
    session.getTransaction().commit()
    session.close()

    expect:
    assertTraces(1) {
      trace(0, 4) {
        span(0) {
          name "Session"
          kind INTERNAL
          hasNoParent()
          attributes {
          }
        }
        span(1) {
          name "Criteria.$methodName"
          kind INTERNAL
          childOf span(0)
          attributes {
          }
        }
        span(2) {
          name ~/^select /
          kind CLIENT
          childOf span(1)
          attributes {
            "${SemanticAttributes.DB_SYSTEM.key()}" "h2"
            "${SemanticAttributes.DB_NAME.key()}" "db1"
            "${SemanticAttributes.DB_USER.key()}" "sa"
            "${SemanticAttributes.DB_STATEMENT.key()}" ~/^select /
            "${SemanticAttributes.DB_CONNECTION_STRING.key()}" "h2:mem:"
          }
        }
        span(3) {
          name "Transaction.commit"
          kind INTERNAL
          childOf span(0)
          attributes {
          }
        }
      }
    }

    where:
    methodName     | interaction
    "list"         | { c -> c.list() }
    "uniqueResult" | { c -> c.uniqueResult() }
    "scroll"       | { c -> c.scroll() }
  }
}
