package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalSession;

/** Retryable ordered close of one SQL session and its runtime lease. */
final class SqlSessionCloseLifecycle {
  private final RelationalSession session;
  private final SqlTransactionState transactions;
  private final SqlQueryExecution queries;
  private final SqlStreamingStatementLifecycle streaming;
  private final SqlTemporalContext temporal;
  private final SqlSessionShapeBudget budget;
  private final SqlSessionRuntimeLease runtimeLease;
  private boolean closing;
  private boolean relationalClosed;
  private boolean closed;

  SqlSessionCloseLifecycle(
      RelationalSession relationalSession,
      SqlTransactionState transactionState,
      SqlQueryExecution queryExecution,
      SqlStreamingStatementLifecycle streamingLifecycle,
      SqlTemporalContext temporalContext,
      SqlSessionShapeBudget shapeBudget,
      SqlSessionRuntimeLease lease) {
    session = relationalSession;
    transactions = transactionState;
    queries = queryExecution;
    streaming = streamingLifecycle;
    temporal = temporalContext;
    budget = shapeBudget;
    runtimeLease = lease;
  }

  boolean unavailable() { return closing || closed; }
  boolean closing() { return closing; }
  boolean closed() { return closed; }

  StatusCode close() {
    if (closed) return StatusCode.CLOSED;
    StatusCode status = StatusCode.OK;
    if (!closing) {
      status = SqlSessionActiveScanClose.close(queries, streaming, temporal);
      if (status.isOk() && transactions.isExplicit()) status = transactions.abortExplicit();
      if (!status.isOk()) return status;
      closing = true;
    }
    status = budget.closeMaterialized(null);
    if (status.isOk() && !relationalClosed) {
      status = session.close();
      if (status.isOk()) relationalClosed = true;
    }
    if (status.isOk()) status = runtimeLease.close();
    if (status.isOk()) closed = true;
    return status;
  }
}
