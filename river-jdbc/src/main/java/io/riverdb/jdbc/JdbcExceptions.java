package io.riverdb.jdbc;

import io.riverdb.base.error.StatusCode;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;

/** Allocating adapters required only at the JDBC boundary. */
final class JdbcExceptions {
  private JdbcExceptions() {
  }

  static void require(StatusCode status, String operation) throws SQLException {
    if (!status.isOk()) {
      throw failure(status, operation);
    }
  }

  static SQLException failure(StatusCode status, String operation) {
    return new SQLException(
        operation + " failed: " + status,
        sqlState(status),
        status.stableCode());
  }

  static SQLException closed(String resource) {
    return new SQLException(resource + " is closed", "08003", StatusCode.CLOSED.stableCode());
  }

  static SQLException invalid(String message) {
    return new SQLException(
        message,
        "22000",
        StatusCode.INVALID_EXTERNAL_INPUT.stableCode());
  }

  static SQLFeatureNotSupportedException unsupported() {
    return new SQLFeatureNotSupportedException("JDBC feature is not supported", "0A000");
  }

  private static String sqlState(StatusCode status) {
    return switch (status) {
      case CLOSED, FENCED, IO_FAILURE, CORRUPTION, INVARIANT_BROKEN -> "08006";
      case CONFLICT, NOT_OWNER, RETRY -> "40001";
      case RESOURCE_EXHAUSTED -> "53000";
      case TIMEOUT -> "HYT00";
      case CANCELLED -> "57014";
      case INVALID_EXTERNAL_INPUT -> "22000";
      case OK -> "00000";
    };
  }
}
