/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.hibernate.v3_3;

import static io.opentelemetry.javaagent.instrumentation.hibernate.HibernateDecorator.DECORATE;
import static io.opentelemetry.javaagent.instrumentation.hibernate.SessionMethodUtils.SCOPE_ONLY_METHODS;
import static io.opentelemetry.javaagent.tooling.bytebuddy.matcher.AgentElementMatchers.hasInterface;
import static io.opentelemetry.javaagent.tooling.bytebuddy.matcher.AgentElementMatchers.implementsInterface;
import static io.opentelemetry.javaagent.tooling.matcher.NameMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.javaagent.instrumentation.api.ContextStore;
import io.opentelemetry.javaagent.instrumentation.api.InstrumentationContext;
import io.opentelemetry.javaagent.instrumentation.api.Java8BytecodeBridge;
import io.opentelemetry.javaagent.instrumentation.api.SpanWithScope;
import io.opentelemetry.javaagent.instrumentation.hibernate.SessionMethodUtils;
import io.opentelemetry.javaagent.tooling.Instrumenter;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.matcher.ElementMatcher;
import org.hibernate.Criteria;
import org.hibernate.Query;
import org.hibernate.Session;
import org.hibernate.StatelessSession;
import org.hibernate.Transaction;

@AutoService(Instrumenter.class)
public class SessionInstrumentation extends AbstractHibernateInstrumentation {

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> map = new HashMap<>();
    map.put("org.hibernate.Session", Context.class.getName());
    map.put("org.hibernate.StatelessSession", Context.class.getName());
    map.put("org.hibernate.Query", Context.class.getName());
    map.put("org.hibernate.Transaction", Context.class.getName());
    map.put("org.hibernate.Criteria", Context.class.getName());
    return Collections.unmodifiableMap(map);
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return implementsInterface(
        namedOneOf("org.hibernate.Session", "org.hibernate.StatelessSession"));
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    Map<ElementMatcher<? super MethodDescription>, String> transformers = new HashMap<>();
    transformers.put(
        isMethod().and(named("close")).and(takesArguments(0)),
        SessionInstrumentation.class.getName() + "$SessionCloseAdvice");

    // Session synchronous methods we want to instrument.
    transformers.put(
        isMethod()
            .and(
                namedOneOf(
                    "save",
                    "replicate",
                    "saveOrUpdate",
                    "update",
                    "merge",
                    "persist",
                    "lock",
                    "refresh",
                    "insert",
                    "delete",
                    // Lazy-load methods.
                    "immediateLoad",
                    "internalLoad")),
        SessionInstrumentation.class.getName() + "$SessionMethodAdvice");

    // Handle the non-generic 'get' separately.
    transformers.put(
        isMethod()
            .and(named("get"))
            .and(returns(named("java.lang.Object")))
            .and(takesArgument(0, named("java.lang.String"))),
        SessionInstrumentation.class.getName() + "$SessionMethodAdvice");

    // These methods return some object that we want to instrument, and so the Advice will pin the
    // current Span to the returned object using a ContextStore.
    transformers.put(
        isMethod()
            .and(namedOneOf("beginTransaction", "getTransaction"))
            .and(returns(named("org.hibernate.Transaction"))),
        SessionInstrumentation.class.getName() + "$GetTransactionAdvice");

    transformers.put(
        isMethod().and(returns(hasInterface(named("org.hibernate.Query")))),
        SessionInstrumentation.class.getName() + "$GetQueryAdvice");

    transformers.put(
        isMethod().and(returns(hasInterface(named("org.hibernate.Criteria")))),
        SessionInstrumentation.class.getName() + "$GetCriteriaAdvice");

    return transformers;
  }

  public static class SessionCloseAdvice extends V3Advice {

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void closeSession(
        @Advice.This Object session, @Advice.Thrown Throwable throwable) {

      Context sessionContext = null;
      if (session instanceof Session) {
        ContextStore<Session, Context> contextStore =
            InstrumentationContext.get(Session.class, Context.class);
        sessionContext = contextStore.get((Session) session);
      } else if (session instanceof StatelessSession) {
        ContextStore<StatelessSession, Context> contextStore =
            InstrumentationContext.get(StatelessSession.class, Context.class);
        sessionContext = contextStore.get((StatelessSession) session);
      }

      if (sessionContext == null) {
        return;
      }
      Span sessionSpan = Java8BytecodeBridge.spanFromContext(sessionContext);

      DECORATE.onError(sessionSpan, throwable);
      DECORATE.beforeFinish(sessionSpan);
      sessionSpan.end();
    }
  }

  public static class SessionMethodAdvice extends V3Advice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static SpanWithScope startMethod(
        @Advice.This Object session,
        @Advice.Origin("#m") String name,
        @Advice.Argument(0) Object entity) {

      boolean startSpan = !SCOPE_ONLY_METHODS.contains(name);
      if (session instanceof Session) {
        ContextStore<Session, Context> contextStore =
            InstrumentationContext.get(Session.class, Context.class);
        return SessionMethodUtils.startScopeFrom(
            contextStore, (Session) session, "Session." + name, entity, startSpan);
      } else if (session instanceof StatelessSession) {
        ContextStore<StatelessSession, Context> contextStore =
            InstrumentationContext.get(StatelessSession.class, Context.class);
        return SessionMethodUtils.startScopeFrom(
            contextStore, (StatelessSession) session, "Session." + name, entity, startSpan);
      }
      return null;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void endMethod(
        @Advice.Enter SpanWithScope spanWithScope,
        @Advice.Thrown Throwable throwable,
        @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object returned,
        @Advice.Origin("#m") String name) {

      SessionMethodUtils.closeScope(spanWithScope, throwable, "Session." + name, returned);
    }
  }

  public static class GetQueryAdvice extends V3Advice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void getQuery(@Advice.This Object session, @Advice.Return Query query) {

      ContextStore<Query, Context> queryContextStore =
          InstrumentationContext.get(Query.class, Context.class);
      if (session instanceof Session) {
        ContextStore<Session, Context> sessionContextStore =
            InstrumentationContext.get(Session.class, Context.class);
        SessionMethodUtils.attachSpanFromStore(
            sessionContextStore, (Session) session, queryContextStore, query);
      } else if (session instanceof StatelessSession) {
        ContextStore<StatelessSession, Context> sessionContextStore =
            InstrumentationContext.get(StatelessSession.class, Context.class);
        SessionMethodUtils.attachSpanFromStore(
            sessionContextStore, (StatelessSession) session, queryContextStore, query);
      }
    }
  }

  public static class GetTransactionAdvice extends V3Advice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void getTransaction(
        @Advice.This Object session, @Advice.Return Transaction transaction) {

      ContextStore<Transaction, Context> transactionContextStore =
          InstrumentationContext.get(Transaction.class, Context.class);

      if (session instanceof Session) {
        ContextStore<Session, Context> sessionContextStore =
            InstrumentationContext.get(Session.class, Context.class);
        SessionMethodUtils.attachSpanFromStore(
            sessionContextStore, (Session) session, transactionContextStore, transaction);
      } else if (session instanceof StatelessSession) {
        ContextStore<StatelessSession, Context> sessionContextStore =
            InstrumentationContext.get(StatelessSession.class, Context.class);
        SessionMethodUtils.attachSpanFromStore(
            sessionContextStore, (StatelessSession) session, transactionContextStore, transaction);
      }
    }
  }

  public static class GetCriteriaAdvice extends V3Advice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void getCriteria(@Advice.This Object session, @Advice.Return Criteria criteria) {

      ContextStore<Criteria, Context> criteriaContextStore =
          InstrumentationContext.get(Criteria.class, Context.class);
      if (session instanceof Session) {
        ContextStore<Session, Context> sessionContextStore =
            InstrumentationContext.get(Session.class, Context.class);
        SessionMethodUtils.attachSpanFromStore(
            sessionContextStore, (Session) session, criteriaContextStore, criteria);
      } else if (session instanceof StatelessSession) {
        ContextStore<StatelessSession, Context> sessionContextStore =
            InstrumentationContext.get(StatelessSession.class, Context.class);
        SessionMethodUtils.attachSpanFromStore(
            sessionContextStore, (StatelessSession) session, criteriaContextStore, criteria);
      }
    }
  }
}
