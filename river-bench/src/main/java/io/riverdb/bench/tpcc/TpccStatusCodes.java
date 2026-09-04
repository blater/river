package io.riverdb.bench.tpcc;

import io.riverdb.base.error.StatusCode;
import java.sql.SQLException;

/** Exact stable-code decoding at the benchmark's JDBC trust boundary. */
final class TpccStatusCodes {
  private static final StatusCode[] VALUES = StatusCode.values();

  private TpccStatusCodes() {}

  static StatusCode decode(SQLException failure) {
    int stableCode = failure.getErrorCode();
    for (StatusCode status : VALUES) {
      if (status.stableCode() == stableCode) return status;
    }
    return null;
  }
}
