package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.tx.api.TransactionOutcome;
import io.riverdb.tx.api.TransactionState;

/** Commits or aborts one bounded index-build batch without losing its primary failure. */
final class RelationalIndexBuildCompletion {
  private RelationalIndexBuildCompletion() {
  }

  static StatusCode finish(
      RelationalSession session, TransactionOutcome outcome, StatusCode status) {
    if (status.isOk()) return session.commitBuildPhase(outcome);
    if (session.indexedSession().transaction().state() != TransactionState.ACTIVE) {
      return status;
    }
    StatusCode abort = session.abortBuildPhase(outcome);
    return abort.isOk() ? status : abort;
  }
}
