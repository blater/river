package io.riverdb.jdbc;

import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.RiverSession;
import java.sql.Connection;
import java.sql.SQLException;

/** Starts a manual JDBC transaction at the first statement boundary. */
final class RiverJdbcTransactionStarter {
  private RiverJdbcTransactionStarter() { }

  static boolean ensure(
      RiverSession session,
      CommandResult result,
      boolean autoCommit,
      boolean transactionActive,
      int isolation) throws SQLException {
    if (autoCommit || transactionActive) {
      return transactionActive;
    }
    String begin = isolation == Connection.TRANSACTION_READ_COMMITTED
        ? "BEGIN READ COMMITTED"
        : isolation == Connection.TRANSACTION_SERIALIZABLE
            ? "BEGIN SERIALIZABLE" : "BEGIN";
    result.reset();
    JdbcExceptions.require(session.execute(begin, result), "begin transaction");
    return true;
  }
}
