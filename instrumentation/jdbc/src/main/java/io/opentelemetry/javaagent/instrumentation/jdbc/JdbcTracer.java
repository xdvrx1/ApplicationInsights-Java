/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.jdbc;

import static io.opentelemetry.javaagent.instrumentation.jdbc.JDBCUtils.connectionFromStatement;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.instrumentation.api.tracer.DatabaseClientTracer;
import io.opentelemetry.javaagent.instrumentation.api.CallDepthThreadLocalMap;
import io.opentelemetry.javaagent.instrumentation.api.CallDepthThreadLocalMap.Depth;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

public class JdbcTracer extends DatabaseClientTracer<DBInfo, String> {
  private static final JdbcTracer TRACER = new JdbcTracer();

  public static JdbcTracer tracer() {
    return TRACER;
  }

  @Override
  protected String getInstrumentationName() {
    return "io.opentelemetry.auto.jdbc";
  }

  @Override
  protected String dbSystem(DBInfo info) {
    return info.getSystem();
  }

  @Override
  protected String dbUser(DBInfo info) {
    return info.getUser();
  }

  @Override
  protected String dbName(DBInfo info) {
    if (info.getName() != null) {
      return info.getName();
    } else {
      return info.getDb();
    }
  }

  // TODO find a way to implement
  @Override
  protected InetSocketAddress peerAddress(DBInfo dbInfo) {
    return null;
  }

  @Override
  protected String dbConnectionString(DBInfo info) {
    return info.getShortUrl();
  }

  public Depth getCallDepth() {
    return CallDepthThreadLocalMap.getCallDepth(Statement.class);
  }

  public Span startSpan(PreparedStatement statement) {
    return startSpan(statement, JDBCMaps.preparedStatements.get(statement));
  }

  public Span startSpan(Statement statement, String query) {
    Connection connection = connectionFromStatement(statement);
    if (connection == null) {
      return null;
    }

    DBInfo dbInfo = extractDbInfo(connection);

    return startSpan(dbInfo, query);
  }

  @Override
  protected String normalizeQuery(String query) {
    return JDBCUtils.normalizeSql(query);
  }

  private DBInfo extractDbInfo(Connection connection) {
    DBInfo dbInfo = JDBCMaps.connectionInfo.get(connection);
    /*
     * Logic to get the DBInfo from a JDBC Connection, if the connection was not created via
     * Driver.connect, or it has never seen before, the connectionInfo map will return null and will
     * attempt to extract DBInfo from the connection. If the DBInfo can't be extracted, then the
     * connection will be stored with the DEFAULT DBInfo as the value in the connectionInfo map to
     * avoid retry overhead.
     */
    {
      if (dbInfo == null) {
        try {
          DatabaseMetaData metaData = connection.getMetaData();
          String url = metaData.getURL();
          if (url != null) {
            try {
              dbInfo = JDBCConnectionUrlParser.parse(url, connection.getClientInfo());
            } catch (Throwable ex) {
              // getClientInfo is likely not allowed.
              dbInfo = JDBCConnectionUrlParser.parse(url, null);
            }
          } else {
            dbInfo = DBInfo.DEFAULT;
          }
        } catch (SQLException se) {
          dbInfo = DBInfo.DEFAULT;
        }
        JDBCMaps.connectionInfo.put(connection, dbInfo);
      }
    }
    return dbInfo;
  }
}
