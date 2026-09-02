package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.tx.api.IsolationLevel;

/** Owns the transaction and statement frame retained by one streaming scan. */
final class SqlStreamingStatementLifecycle {
  private static final int IDLE = 0;
  private static final int TRANSACTION_PENDING = 1;
  private static final int SAVEPOINT_ACTIVE = 2;
  private static final int STATEMENT_ACTIVE = 3;
  private static final int STATEMENT_COMPLETED = 4;
  private static final int WAIT_CANCELLED = 5;
  private static final int SAVEPOINT_ROLLED_BACK = 6;

  private final RelationalSession session;
  private final SqlTransactionState transactions;
  private int phase;
  private boolean implicit;
  private boolean bodyStatusCaptured;
  private StatusCode pendingBodyStatus = StatusCode.OK;

  SqlStreamingStatementLifecycle(
      RelationalSession relationalSession,
      SqlTransactionState transactionState) {
    session = relationalSession;
    transactions = transactionState;
  }

  StatusCode begin() {
    if (phase != IDLE) {
      return StatusCode.CONFLICT;
    }
    implicit = !transactions.isExplicit();
    StatusCode status;
    if (implicit) {
      status = transactions.beginImplicit(IsolationLevel.READ_COMMITTED);
      if (!status.isOk()) {
        clear();
        return status;
      }
      phase = TRANSACTION_PENDING;
    } else {
      status = transactions.createStatementSavepoint();
      if (!status.isOk()) {
        clear();
        return status;
      }
      phase = SAVEPOINT_ACTIVE;
    }
    status = transactions.beginStatement();
    if (status.isOk()) {
      phase = STATEMENT_ACTIVE;
      return status;
    }
    captureBodyStatus(status);
    return implicit ? finishImplicit() : finishExplicit();
  }

  boolean implicit() {
    return implicit;
  }

  boolean explicit() {
    return transactions.isExplicit();
  }

  boolean isActive() {
    return phase != IDLE;
  }

  boolean statementCompleted() {
    return phase == STATEMENT_COMPLETED;
  }

  StatusCode failStart(StatusCode status) {
    return failStart(status, true);
  }

  StatusCode failStart(
      StatusCode status, boolean physicalCleanupComplete) {
    captureBodyStatus(status);
    if (!physicalCleanupComplete) {
      return status;
    }
    return finishFrame(null);
  }

  StatusCode finish(StatusCode status, SqlExecutionResult result) {
    return finish(status, status.isOk(), result);
  }

  StatusCode finish(
      StatusCode status,
      boolean physicalCleanupComplete,
      SqlExecutionResult result) {
    return finish(status, physicalCleanupComplete, result, true);
  }

  StatusCode finishDelivered(
      StatusCode status,
      boolean physicalCleanupComplete,
      SqlExecutionResult result) {
    return finish(status, physicalCleanupComplete, result, false);
  }

  private StatusCode finish(
      StatusCode status,
      boolean physicalCleanupComplete,
      SqlExecutionResult result,
      boolean returnBodyStatus) {
    if (phase == IDLE) {
      return StatusCode.CONFLICT;
    }
    if (!physicalCleanupComplete) {
      return status;
    }
    captureBodyStatus(status);
    return finishFrame(result, returnBodyStatus);
  }

  private StatusCode finishFrame(SqlExecutionResult result) {
    return finishFrame(result, true);
  }

  private StatusCode finishFrame(
      SqlExecutionResult result, boolean returnBodyStatus) {
    if (phase == STATEMENT_ACTIVE) {
      StatusCode completed = transactions.completeStatement();
      if (!completed.isOk()) {
        return completed;
      }
      phase = STATEMENT_COMPLETED;
    }
    return implicit
        ? finishImplicit(result, returnBodyStatus)
        : finishExplicit(result, returnBodyStatus);
  }

  private StatusCode finishImplicit() {
    return finishImplicit(null);
  }

  private StatusCode finishImplicit(SqlExecutionResult result) {
    return finishImplicit(result, true);
  }

  private StatusCode finishImplicit(
      SqlExecutionResult result, boolean returnBodyStatus) {
    if (phase != TRANSACTION_PENDING && phase != STATEMENT_COMPLETED) {
      return StatusCode.CONFLICT;
    }
    StatusCode terminal = pendingBodyStatus.isOk()
        ? transactions.commitImplicit() : transactions.abortImplicit();
    StatusCode status = terminal.isOk()
        ? returnBodyStatus ? pendingBodyStatus : StatusCode.OK
        : terminal;
    if (terminal.isOk() && pendingBodyStatus.isOk() && result != null) {
      result.setTransaction(false, transactions.commitSequence());
    }
    if (!session.transactionActive()) clear();
    return status;
  }

  private StatusCode finishExplicit() {
    return finishExplicit(null);
  }

  private StatusCode finishExplicit(SqlExecutionResult result) {
    return finishExplicit(result, true);
  }

  private StatusCode finishExplicit(
      SqlExecutionResult result, boolean returnBodyStatus) {
    StatusCode status;
    if (pendingBodyStatus.isOk()) {
      if (phase != STATEMENT_COMPLETED) return StatusCode.CONFLICT;
    } else {
      if (phase == SAVEPOINT_ACTIVE || phase == STATEMENT_COMPLETED) {
        status = session.cancelLockWait();
        if (!status.isOk()) return status;
        phase = WAIT_CANCELLED;
      }
      if (phase == WAIT_CANCELLED) {
        status = transactions.rollbackStatementSavepoint();
        if (!status.isOk()) return status;
        phase = SAVEPOINT_ROLLED_BACK;
      }
      if (phase != SAVEPOINT_ROLLED_BACK) return StatusCode.CONFLICT;
    }
    status = transactions.releaseStatementSavepoint();
    if (!status.isOk()) return status;
    if (result != null) {
      result.setTransaction(true, session.visibleCommitSequence());
    }
    StatusCode resultStatus = returnBodyStatus ? pendingBodyStatus : StatusCode.OK;
    clear();
    return resultStatus;
  }

  private void captureBodyStatus(StatusCode status) {
    if (!bodyStatusCaptured) {
      pendingBodyStatus = status;
      bodyStatusCaptured = true;
    }
  }

  private void clear() {
    phase = IDLE;
    implicit = false;
    bodyStatusCaptured = false;
    pendingBodyStatus = StatusCode.OK;
  }
}
