package io.riverdb.jdbc;

import io.riverdb.base.error.StatusCode;
import java.sql.SQLException;

/** Fixed connection-owned registry for live JDBC statements. */
final class RiverJdbcStatementRegistry {
  static final int MAXIMUM_STATEMENTS = 64;
  private final RiverJdbcStatement[] statements =
      new RiverJdbcStatement[MAXIMUM_STATEMENTS];

  void register(RiverJdbcStatement statement) throws SQLException {
    if (statement == null) throw JdbcExceptions.invalid("statement must not be null");
    for (int index = 0; index < statements.length; index++) {
      if (statements[index] == null) {
        statements[index] = statement;
        return;
      }
    }
    throw JdbcExceptions.failure(StatusCode.RESOURCE_EXHAUSTED, "open statement");
  }

  void unregister(RiverJdbcStatement statement) {
    if (statement == null) return;
    for (int index = 0; index < statements.length; index++) {
      if (statements[index] == statement) {
        statements[index] = null;
        return;
      }
    }
  }

  void closeOpenResults() throws SQLException {
    SQLException failure = null;
    for (RiverJdbcStatement statement : statements) {
      if (statement == null) continue;
      try {
        statement.closeOpenResult();
      } catch (SQLException closeFailure) {
        if (failure == null) failure = closeFailure;
      }
    }
    if (failure != null) {
      throw failure;
    }
  }

  SQLException closeAll() {
    SQLException failure = null;
    for (int index = 0; index < statements.length; index++) {
      RiverJdbcStatement statement = statements[index];
      if (statement == null) continue;
      try {
        statement.close();
      } catch (SQLException closeFailure) {
        if (failure == null) failure = closeFailure;
      } finally {
        // A failed statement close must not retain the statement or consume a slot.
        if (statements[index] == statement) statements[index] = null;
      }
    }
    return failure;
  }
}
