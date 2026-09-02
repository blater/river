package io.riverdb.bench.tpcc;

import io.riverdb.base.error.StatusCode;
import java.sql.SQLException;

/** Converts River status values only at the benchmark's JDBC-facing boundary. */
final class TpccRiverStatus {
  private TpccRiverStatus() { }

  static void require(StatusCode status, String operation) throws SQLException {
    if (!status.isOk()) throw failure(status, operation);
  }

  static SQLException failure(StatusCode status, String operation) {
    return new SQLException(operation + " failed: " + status, "HY000", status.stableCode());
  }
}
