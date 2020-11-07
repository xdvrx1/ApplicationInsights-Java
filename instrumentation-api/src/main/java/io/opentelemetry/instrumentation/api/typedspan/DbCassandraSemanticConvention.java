/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.api.typedspan;

import io.opentelemetry.api.trace.Span;

public interface DbCassandraSemanticConvention {
  void end();

  Span getSpan();

  /**
   * Sets a value for db.system
   *
   * @param dbSystem An identifier for the database management system (DBMS) product being used. See
   *     below for a list of well-known identifiers.
   */
  DbCassandraSemanticConvention setDbSystem(String dbSystem);

  /**
   * Sets a value for db.connection_string
   *
   * @param dbConnectionString The connection string used to connect to the database.
   *     <p>It is recommended to remove embedded credentials.
   */
  DbCassandraSemanticConvention setDbConnectionString(String dbConnectionString);

  /**
   * Sets a value for db.user
   *
   * @param dbUser Username for accessing the database.
   */
  DbCassandraSemanticConvention setDbUser(String dbUser);

  /**
   * Sets a value for db.jdbc.driver_classname
   *
   * @param dbJdbcDriverClassname The fully-qualified class name of the [Java Database Connectivity
   *     (JDBC)](https://docs.oracle.com/javase/8/docs/technotes/guides/jdbc/) driver used to
   *     connect.
   */
  DbCassandraSemanticConvention setDbJdbcDriverClassname(String dbJdbcDriverClassname);

  /**
   * Sets a value for db.name
   *
   * @param dbName If no tech-specific attribute is defined, this attribute is used to report the
   *     name of the database being accessed. For commands that switch the database, this should be
   *     set to the target database (even if the command fails).
   *     <p>In some SQL databases, the database name to be used is called "schema name".
   */
  DbCassandraSemanticConvention setDbName(String dbName);

  /**
   * Sets a value for db.statement
   *
   * @param dbStatement The database statement being executed.
   *     <p>The value may be sanitized to exclude sensitive information.
   */
  DbCassandraSemanticConvention setDbStatement(String dbStatement);

  /**
   * Sets a value for db.operation
   *
   * @param dbOperation The name of the operation being executed, e.g. the [MongoDB command
   *     name](https://docs.mongodb.com/manual/reference/command/#database-operations) such as
   *     `findAndModify`.
   *     <p>While it would semantically make sense to set this, e.g., to a SQL keyword like `SELECT`
   *     or `INSERT`, it is not recommended to attempt any client-side parsing of `db.statement`
   *     just to get this property (the back end can do that if required).
   */
  DbCassandraSemanticConvention setDbOperation(String dbOperation);

  /**
   * Sets a value for net.peer.name
   *
   * @param netPeerName Remote hostname or similar, see note below.
   */
  DbCassandraSemanticConvention setNetPeerName(String netPeerName);

  /**
   * Sets a value for net.peer.ip
   *
   * @param netPeerIp Remote address of the peer (dotted decimal for IPv4 or
   *     [RFC5952](https://tools.ietf.org/html/rfc5952) for IPv6).
   */
  DbCassandraSemanticConvention setNetPeerIp(String netPeerIp);

  /**
   * Sets a value for net.peer.port
   *
   * @param netPeerPort Remote port number.
   */
  DbCassandraSemanticConvention setNetPeerPort(long netPeerPort);

  /**
   * Sets a value for net.transport
   *
   * @param netTransport Transport protocol used. See note below.
   */
  DbCassandraSemanticConvention setNetTransport(String netTransport);

  /**
   * Sets a value for db.cassandra.keyspace
   *
   * @param dbCassandraKeyspace The name of the keyspace being accessed. To be used instead of the
   *     generic `db.name` attribute.
   */
  DbCassandraSemanticConvention setDbCassandraKeyspace(String dbCassandraKeyspace);
}
