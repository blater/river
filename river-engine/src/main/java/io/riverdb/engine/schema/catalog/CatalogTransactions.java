package io.riverdb.engine.schema.catalog;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.EmbeddedDatabase;
import io.riverdb.engine.EmbeddedSessionOpenResult;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.format.catalog.CatalogDefinitionRecordCodec;
import io.riverdb.tx.api.TransactionOutcome;
import io.riverdb.tx.api.TransactionState;

/** Reusable embedded session acquisition and terminal transaction handling. */
final class CatalogTransactions {
  private static final int MAXIMUM_BORROWED_SESSIONS = 2;
  private final EmbeddedDatabase embedded;
  private final EmbeddedSessionOpenResult sessionResult = new EmbeddedSessionOpenResult();
  private final TransactionOutcome outcome = new TransactionOutcome();
  private final IndexedTransactionSession[] retainedSessions =
      new IndexedTransactionSession[MAXIMUM_BORROWED_SESSIONS];
  private final boolean[] borrowedSessions = new boolean[MAXIMUM_BORROWED_SESSIONS];
  private final boolean[] buildSessions = new boolean[MAXIMUM_BORROWED_SESSIONS];
  private final boolean[] reusableSessions = new boolean[MAXIMUM_BORROWED_SESSIONS];
  private TransactionState lastState = TransactionState.ABORTED;

  CatalogTransactions(EmbeddedDatabase database) {
    embedded = database;
  }

  synchronized StatusCode open(CatalogSessionResult result) {
    return open(result, false);
  }

  synchronized StatusCode openBuild(CatalogSessionResult result) {
    return open(result, true);
  }

  private StatusCode open(CatalogSessionResult result, boolean build) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    int slot = availableSlot();
    if (slot < 0) return StatusCode.CONFLICT;
    if (retainedSessions[slot] == null) {
      StatusCode status = createSession(slot);
      if (!status.isOk()) return status;
    }
    StatusCode status = embedded.admitSession(retainedSessions[slot]);
    if (!status.isOk()) return status;
    borrowedSessions[slot] = true;
    buildSessions[slot] = build;
    result.set(retainedSessions[slot]);
    return StatusCode.OK;
  }

  synchronized StatusCode finish(
      IndexedTransactionSession session, StatusCode status, boolean commit) {
    lastState = TransactionState.ABORTED;
    if (session == null) return status;
    int slot = retainedSlot(session);
    if (slot < 0 || !borrowedSessions[slot]) return StatusCode.INVALID_EXTERNAL_INPUT;
    outcome.reset();
    if (status.isOk()) {
      StatusCode terminal = commit ? session.commit(outcome) : session.abort(outcome);
      captureState(session, true);
      return release(slot, session, terminal, true);
    }
    if (session.transaction().state() != TransactionState.ACTIVE) {
      captureState(session, false);
      return release(slot, session, status, false);
    }
    StatusCode abort = session.abort(outcome);
    captureState(session, true);
    return release(slot, session, abort.isOk() ? status : abort, true);
  }

  synchronized TransactionState lastState() {
    return lastState;
  }

  synchronized StatusCode releaseBuild(IndexedTransactionSession session) {
    int slot = retainedSlot(session);
    if (slot < 0 || !borrowedSessions[slot] || !buildSessions[slot]
        || session.transaction().isActiveHandle()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    borrowedSessions[slot] = false;
    buildSessions[slot] = false;
    if (!reusableSessions[slot]) {
      StatusCode closed = retainedSessions[slot].close();
      if (!closed.isOk() && closed != StatusCode.CLOSED) return closed;
      retainedSessions[slot] = null;
    }
    return StatusCode.OK;
  }

  private void captureState(IndexedTransactionSession session, boolean terminalAttempted) {
    if (outcome.isAvailable()) {
      lastState = outcome.state();
      return;
    }
    TransactionState state = session.transaction().state();
    if (!terminalAttempted) {
      lastState = TransactionState.ABORTED;
      return;
    }
    lastState = state == TransactionState.COMMITTED || state == TransactionState.ABORTED
        ? state : TransactionState.INDETERMINATE;
  }

  private StatusCode release(
      int slot, IndexedTransactionSession session, StatusCode status,
      boolean terminalAttempted) {
    TransactionState state = session.transaction().state();
    boolean reusable = terminalAttempted && !session.transaction().isActiveHandle()
        && (state == TransactionState.COMMITTED || state == TransactionState.ABORTED);
    reusableSessions[slot] = reusable;
    if (!buildSessions[slot]) {
      borrowedSessions[slot] = false;
      if (!reusable) {
        StatusCode closed = retainedSessions[slot].close();
        if (!closed.isOk() && closed != StatusCode.CLOSED) return closed;
        retainedSessions[slot] = null;
      }
    }
    return status;
  }

  private int availableSlot() {
    for (int slot = 0; slot < retainedSessions.length; slot++) {
      if (!borrowedSessions[slot]) return slot;
    }
    return -1;
  }

  private int retainedSlot(IndexedTransactionSession session) {
    for (int slot = 0; slot < retainedSessions.length; slot++) {
      if (retainedSessions[slot] == session) return slot;
    }
    return -1;
  }

  private StatusCode createSession(int slot) {
    sessionResult.reset();
    StatusCode status = embedded.createSession(
        CatalogDefinitionRecordCodec.MAX_RECORD_BYTES, sessionResult);
    if (status.isOk()) {
      retainedSessions[slot] = sessionResult.session();
      reusableSessions[slot] = true;
    }
    return status;
  }

  synchronized StatusCode close() {
    for (boolean borrowed : borrowedSessions) if (borrowed) return StatusCode.CONFLICT;
    for (int slot = 0; slot < retainedSessions.length; slot++) {
      IndexedTransactionSession session = retainedSessions[slot];
      if (session == null) continue;
      StatusCode status = session.close();
      if (!status.isOk() && status != StatusCode.CLOSED) return status;
      retainedSessions[slot] = null;
      reusableSessions[slot] = false;
    }
    return StatusCode.OK;
  }

}
