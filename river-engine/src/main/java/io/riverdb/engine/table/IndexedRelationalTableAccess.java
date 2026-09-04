package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;

/** Relational tuple commit surface inherited by the synchronized indexed-table facade. */
abstract class IndexedRelationalTableAccess {
  private final IndexedTableStore store;

  IndexedRelationalTableAccess(IndexedTableStore tableStore) {
    store = tableStore;
  }

  synchronized StatusCode commitRelational(
      long transactionId,
      IndexedRelationalMutation mutations,
      long oldestVisibleCommitSequence,
      IndexedCommitResult result) {
    return store.commitRelational(
        transactionId, mutations, oldestVisibleCommitSequence, result);
  }

  synchronized StatusCode commitHybrid(
      IndexedPreparedLogicalCommit preparedCommit,
      long oldestVisibleCommitSequence,
      IndexedCommitResult result) {
    return store.commitHybrid(
        preparedCommit, oldestVisibleCommitSequence, result);
  }
}
