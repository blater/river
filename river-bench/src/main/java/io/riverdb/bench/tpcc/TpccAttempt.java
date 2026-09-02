package io.riverdb.bench.tpcc;

import java.sql.SQLException;

@FunctionalInterface
interface TpccAttempt {
  /** Returns true for a commit and false for the required invalid-item rollback. */
  boolean execute() throws SQLException;
}
