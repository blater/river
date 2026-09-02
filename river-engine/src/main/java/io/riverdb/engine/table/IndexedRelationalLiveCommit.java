package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;

/** Owns live relational admission, grouped durability, and store publication frontier. */
final class IndexedRelationalLiveCommit {
  private static final long BOOTSTRAP_TRANSACTION_ID = 1;
  private final IndexedTableStore store;
  private final IndexedRelationalCommitCoordinator commit;

  IndexedRelationalLiveCommit(
      IndexedTableStore table, IndexedRelationalCommitCoordinator coordinator) {
    store = table;
    commit = coordinator;
  }

  StatusCode apply(
      long transactionId,
      IndexedRelationalMutation mutations,
      long oldestVisibleCommitSequence,
      IndexedCommitResult result) {
    if (transactionId <= BOOTSTRAP_TRANSACTION_ID || mutations == null
        || !mutations.sealed() || oldestVisibleCommitSequence < 0
        || result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    StatusCode status = store.admission();
    if (status.isOk() && !store.phase.beginStaged()) status = StatusCode.CONFLICT;
    long sequence = status.isOk() ? store.nextCommitSequence() : 0;
    if (status.isOk()) status = commit.commit(
        transactionId, sequence, sequence, oldestVisibleCommitSequence,
        mutations.buffer());
    if (commit.failureFences()) store.failed = true;
    if (!store.failed && store.phase.operationActive()) store.phase.reset();
    if (status.isOk()) {
      store.lastCommitSequence = sequence;
      result.set(0, sequence);
    }
    return status;
  }

  long walCopiedPayloadBytes() { return commit.walCopiedPayloadBytes(); }
}
