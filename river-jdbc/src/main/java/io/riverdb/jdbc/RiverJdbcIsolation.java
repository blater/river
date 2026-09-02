package io.riverdb.jdbc;

import io.riverdb.engine.api.IsolationLevel;
import java.sql.Connection;
import java.sql.SQLException;

/** Validates the bounded JDBC isolation levels supported by River. */
final class RiverJdbcIsolation {
  private RiverJdbcIsolation() { }

  static void requireSupported(int level) throws SQLException {
    if (level != Connection.TRANSACTION_READ_COMMITTED
        && level != Connection.TRANSACTION_REPEATABLE_READ
        && level != Connection.TRANSACTION_SERIALIZABLE) {
      throw JdbcExceptions.unsupported();
    }
  }

  static IsolationLevel toRiver(int level) {
    return switch (level) {
      case Connection.TRANSACTION_READ_COMMITTED -> IsolationLevel.READ_COMMITTED;
      case Connection.TRANSACTION_REPEATABLE_READ -> IsolationLevel.REPEATABLE_READ;
      case Connection.TRANSACTION_SERIALIZABLE -> IsolationLevel.SERIALIZABLE;
      default -> null;
    };
  }
}
