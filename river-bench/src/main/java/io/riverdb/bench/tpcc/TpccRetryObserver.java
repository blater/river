package io.riverdb.bench.tpcc;

import io.riverdb.base.error.StatusCode;
import java.sql.SQLException;

/** Allocation-stable attempt/retry correlation owned by one terminal. */
interface TpccRetryObserver {
  TpccRetryObserver NONE = new TpccRetryObserver() {
    @Override
    public void attemptStarted(int attempt) throws SQLException {}

    @Override
    public void retryableOutcome(
        StatusCode status, boolean clientWillRetry, boolean measuredOutcome)
        throws SQLException {}
  };

  void attemptStarted(int attempt) throws SQLException;

  void retryableOutcome(
      StatusCode status, boolean clientWillRetry, boolean measuredOutcome)
      throws SQLException;
}
