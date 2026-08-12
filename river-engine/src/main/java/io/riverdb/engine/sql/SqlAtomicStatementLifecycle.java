package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.tx.api.IsolationLevel;

/** Owns one retry-bounded non-streaming SQL statement frame. */
final class SqlAtomicStatementLifecycle {
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

  SqlAtomicStatementLifecycle(
      RelationalSession relationalSession,
      SqlTransactionState transactionState) {
    session = relationalSession;
    transactions = transactionState;
  }

  StatusCode begin(IsolationLevel isolation) {
    if (phase != IDLE) {
      return StatusCode.CONFLICT;
    }
    implicit = !transactions.isExplicit();
    StatusCode status;
    if (implicit) {
      status = transactions.beginImplicit(isolation);
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

  StatusCode finish(StatusCode bodyStatus) {
    if (phase == IDLE) {
      return StatusCode.CONFLICT;
    }
    captureBodyStatus(bodyStatus);
    if (phase == STATEMENT_ACTIVE) {
      StatusCode completed = transactions.completeStatement();
      if (!completed.isOk()) {
        return completed;
      }
      phase = STATEMENT_COMPLETED;
    }
    return implicit ? finishImplicit() : finishExplicit();
  }

  boolean isActive() {
    return phase != IDLE;
  }

  StatusCode retry() {
    if (phase == IDLE) {
      return StatusCode.OK;
    }
    if (!bodyStatusCaptured) {
      return StatusCode.CONFLICT;
    }
    return finish(pendingBodyStatus);
  }

  private StatusCode finishImplicit() {
    if (phase != TRANSACTION_PENDING
        && phase != STATEMENT_COMPLETED) {
      return StatusCode.CONFLICT;
    }
    StatusCode terminal = pendingBodyStatus.isOk()
        ? transactions.commitImplicit() : transactions.abortImplicit();
    StatusCode status = terminal.isOk() ? pendingBodyStatus : terminal;
    // A RelationalSession terminal attempt releases its registration even
    // when a later terminal cleanup step reports a non-OK status.
    clear();
    return status;
  }

  private StatusCode finishExplicit() {
    StatusCode status;
    if (pendingBodyStatus.isOk()) {
      if (phase != STATEMENT_COMPLETED) {
        return StatusCode.CONFLICT;
      }
    } else {
      if (phase == SAVEPOINT_ACTIVE || phase == STATEMENT_COMPLETED) {
        status = session.cancelLockWait();
        if (!status.isOk()) {
          return status;
        }
        phase = WAIT_CANCELLED;
      }
      if (phase == WAIT_CANCELLED) {
        status = transactions.rollbackStatementSavepoint();
        if (!status.isOk()) {
          return status;
        }
        phase = SAVEPOINT_ROLLED_BACK;
      }
      if (phase != SAVEPOINT_ROLLED_BACK) {
        return StatusCode.CONFLICT;
      }
    }
    status = transactions.releaseStatementSavepoint();
    if (!status.isOk()) {
      return status;
    }
    StatusCode result = pendingBodyStatus;
    clear();
    return result;
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
