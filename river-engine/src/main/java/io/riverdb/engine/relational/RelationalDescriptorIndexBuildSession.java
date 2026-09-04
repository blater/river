package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.EmbeddedDatabase;
import io.riverdb.engine.EmbeddedSessionOpenResult;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import io.riverdb.tx.api.TransactionState;

/** Reusable internal transaction owner for bounded private-index build batches. */
final class RelationalDescriptorIndexBuildSession {
  private final EmbeddedDatabase database;
  private final EmbeddedSessionOpenResult opened = new EmbeddedSessionOpenResult();
  private final TransactionOutcome outcome = new TransactionOutcome();
  private IndexedTransactionSession session;

  RelationalDescriptorIndexBuildSession(EmbeddedDatabase embedded) {
    database = embedded;
  }

  StatusCode begin(int mutations, int payloadBytes) {
    if (mutations <= 0 || payloadBytes < 0 || active()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = ensureSession();
    if (status.isOk()) status = session.begin(IsolationLevel.SERIALIZABLE);
    if (status.isOk()) status = session.preflightTupleMutations(
        mutations, 1, payloadBytes);
    if (status.isOk()) status = session.preflightTupleIndexLifecycles(1);
    return status.isOk() ? status : finish(status, false);
  }

  IndexedTransactionSession indexed() { return session; }

  StatusCode finish(StatusCode status, boolean commit) {
    if (!active()) return status;
    outcome.reset();
    if (!status.isOk() || !commit) {
      StatusCode aborted = session.abort(outcome);
      return status.isOk() ? aborted : aborted.isOk() ? status : aborted;
    }
    StatusCode committed = session.commit(outcome);
    if (!committed.isOk() && active()) {
      StatusCode aborted = session.abort(outcome);
      if (!aborted.isOk()) return aborted;
    }
    return committed;
  }

  private StatusCode ensureSession() {
    if (session != null) return database.admitSession(session);
    StatusCode status = database.createSession(
        TableSchema.MAXIMUM_ROW_BYTES, opened);
    if (status.isOk()) session = opened.session();
    return status;
  }

  private boolean active() {
    return session != null
        && session.transaction().state() == TransactionState.ACTIVE;
  }

  StatusCode close() {
    if (active()) return StatusCode.CONFLICT;
    if (session == null) return StatusCode.OK;
    StatusCode status = session.close();
    if (status.isOk()) session = null;
    return status;
  }
}
