package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;

/** Reserves unpublished row bytes before taking a mutation key lock. */
final class PendingMutationAdmission {
  private PendingMutationAdmission() {}

  static StatusCode reserveAndLock(
      IndexedTransactionSession session,
      long space,
      long key,
      int rowBytes) {
    StatusCode status = session.reservePending(1, rowBytes);
    return status.isOk() ? session.acquireExclusiveKey(space, key) : status;
  }
}
