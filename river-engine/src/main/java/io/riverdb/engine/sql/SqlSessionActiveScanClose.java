package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;

/** Closes the one retained streaming scan before a session releases its runtime lease. */
final class SqlSessionActiveScanClose {
  private SqlSessionActiveScanClose() { }

  static StatusCode close(
      SqlQueryExecution queries,
      SqlStreamingStatementLifecycle streaming,
      SqlTemporalContext temporal) {
    if (!queries.hasActiveScan()) return StatusCode.OK;
    StatusCode cleanup = queries.retryFailedStartCleanup();
    boolean complete = cleanup.isOk();
    StatusCode terminal = queries.terminalStatus();
    if (complete) queries.completeFailedStart();
    StatusCode status = terminal != null && complete
        ? streaming.finishDelivered(terminal, true, queries.aggregateExecution())
        : streaming.finish(cleanup, complete, queries.aggregateExecution());
    if (complete && !streaming.isActive()) temporal.finishStatement();
    return status;
  }
}
