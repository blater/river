package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.tx.api.TransactionOutcome;

/** Retains and retries one internal transaction until its session is released. */
final class RelationalInternalSessionOwner {
  private RelationalSession pending;
  private TransactionOutcome outcome;
  private boolean commit;

  synchronized StatusCode finish(
      RelationalSession session,
      TransactionOutcome terminalOutcome,
      StatusCode bodyStatus) {
    return finish(session, terminalOutcome, bodyStatus, true);
  }

  synchronized StatusCode finish(
      RelationalSession session,
      TransactionOutcome terminalOutcome,
      StatusCode bodyStatus,
      boolean commitOnSuccess) {
    if (session == null || terminalOutcome == null || pending != null) {
      return StatusCode.CONFLICT;
    }
    pending = session;
    outcome = terminalOutcome;
    commit = bodyStatus.isOk() && commitOnSuccess;
    StatusCode terminal = advance();
    return bodyStatus.isOk() || !terminal.isOk() ? terminal : bodyStatus;
  }

  synchronized StatusCode retry() {
    return pending == null ? StatusCode.OK : advance();
  }

  private StatusCode advance() {
    StatusCode terminal = StatusCode.OK;
    if (pending.transactionActive()) {
      terminal = commit ? pending.commit(outcome) : pending.abort(outcome);
    }
    if (pending.transactionActive()) return terminal;
    StatusCode closed = pending.close();
    if (closed.isOk() || closed == StatusCode.CLOSED) clear();
    return terminal.isOk() ? closed == StatusCode.CLOSED ? StatusCode.OK : closed : terminal;
  }

  private void clear() {
    pending = null;
    outcome = null;
    commit = false;
  }
}
