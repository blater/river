package io.riverdb.jdbc;

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
}
