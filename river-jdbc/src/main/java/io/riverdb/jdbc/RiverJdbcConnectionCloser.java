package io.riverdb.jdbc;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.RiverSession;
import java.sql.SQLException;

/** Closes the JDBC connection's result, statement, session, and transport state. */
final class RiverJdbcConnectionCloser {
  private RiverJdbcConnectionCloser() { }

  static void close(RiverJdbcConnection connection) throws SQLException {
    SQLException closeFailure = closeResults(connection);
    StatusCode sessionStatus = connection.session.close();
    StatusCode connectionStatus = connection.client.close();
    connection.completeSavepointsFrom(0);
    connection.closed = true;
    if (closeFailure != null) {
      throw closeFailure;
    }
    if (!sessionStatus.isOk() && sessionStatus != StatusCode.CLOSED) {
      throw JdbcExceptions.failure(sessionStatus, "close session");
    }
    if (!connectionStatus.isOk() && connectionStatus != StatusCode.CLOSED) {
      throw JdbcExceptions.failure(connectionStatus, "close connection");
    }
  }

  private static SQLException closeResults(RiverJdbcConnection connection) {
    SQLException failure = null;
    if (connection.metadataResult != null) {
      try {
        connection.metadataResult.close();
      } catch (SQLException closeFailure) {
        failure = closeFailure;
      }
    }
    if (connection.statement != null) {
      try {
        connection.statement.close();
      } catch (SQLException closeFailure) {
        if (failure == null) {
          failure = closeFailure;
        }
      }
    }
    return failure;
  }
}
