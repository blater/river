package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.tx.api.IsolationLevel;

/** Owns the transaction and statement frame retained by one streaming scan. */
final class SqlStreamingStatementLifecycle {
  private static final int IDLE = 0;
  private static final int STATEMENT_ACTIVE = 1;
  private static final int TRANSACTION_PENDING = 2;

  private final RelationalSession session;
  private final SqlTransactionState transactions;
  private int phase;
  private boolean implicit;

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
    StatusCode status = implicit
        ? transactions.beginImplicit(IsolationLevel.READ_COMMITTED)
        : StatusCode.OK;
    boolean implicitActive = status.isOk() && implicit;
    if (status.isOk()) {
      status = transactions.beginStatement();
    }
    if (status.isOk()) {
      phase = STATEMENT_ACTIVE;
      return status;
    }
    if (implicitActive && session.isTransactionActive()) {
      StatusCode abort = transactions.abortImplicit();
      clear();
      if (!abort.isOk()) {
        status = abort;
      }
    } else {
      clear();
    }
    return status;
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
    return phase == TRANSACTION_PENDING;
  }

  StatusCode failStart(StatusCode status) {
    return failStart(status, true);
  }

  StatusCode failStart(
      StatusCode status, boolean physicalCleanupComplete) {
    if (!physicalCleanupComplete) {
      return status;
    }
    if (phase == STATEMENT_ACTIVE) {
      StatusCode completed = transactions.completeStatement();
      if (!completed.isOk()) {
        return completed;
      }
      phase = TRANSACTION_PENDING;
    }
    if (phase == TRANSACTION_PENDING
        && implicit
        && session.isTransactionActive()) {
      StatusCode abort = transactions.abortImplicit();
      clear();
      if (!abort.isOk()) {
        return abort;
      }
      return status;
    }
    clear();
    return status;
  }

  StatusCode finish(StatusCode status, SqlExecutionResult result) {
    return finish(status, status.isOk(), result);
  }

  StatusCode finish(
      StatusCode status,
      boolean physicalCleanupComplete,
      SqlExecutionResult result) {
    if (phase == IDLE) {
      return StatusCode.CONFLICT;
    }
    if (!physicalCleanupComplete) {
      return status;
    }
    if (phase == STATEMENT_ACTIVE) {
      StatusCode completed = transactions.completeStatement();
      if (!completed.isOk()) {
        return completed;
      }
      phase = TRANSACTION_PENDING;
    }
    if (!status.isOk()) {
      return status;
    }
    if (implicit) {
      status = transactions.commitImplicit();
      if (status.isOk()) {
        result.setTransaction(false, transactions.commitSequence());
      }
    } else {
      result.setTransaction(true, session.visibleCommitSequence());
    }
    // RelationalSession terminal attempts unregister the transaction even
    // when a later cleanup step supplies a non-OK status. Retrying would
    // otherwise double-commit an already terminal transaction.
    clear();
    return status;
  }

  private void clear() {
    phase = IDLE;
    implicit = false;
  }
}
