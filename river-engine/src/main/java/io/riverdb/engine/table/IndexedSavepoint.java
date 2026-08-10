package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;

/** Caller-owned authenticated position in one indexed transaction's pending write set. */
public final class IndexedSavepoint {
  private IndexedTransactionSession owner;
  private long transactionId;
  private int pendingMutationCount;
  private boolean active;

  public StatusCode reset() {
    if (active) {
      return StatusCode.CONFLICT;
    }
    owner = null;
    transactionId = 0;
    pendingMutationCount = 0;
    return StatusCode.OK;
  }

  StatusCode claim(
      IndexedTransactionSession session,
      long ownerTransactionId,
      int mutations) {
    if (active) {
      return StatusCode.CONFLICT;
    }
    owner = session;
    transactionId = ownerTransactionId;
    pendingMutationCount = mutations;
    active = true;
    return StatusCode.OK;
  }

  boolean isOwnedBy(IndexedTransactionSession session, long ownerTransactionId) {
    return active && owner == session && transactionId == ownerTransactionId;
  }

  int pendingMutationCount() {
    return pendingMutationCount;
  }

  void complete() {
    active = false;
  }

  public boolean isActive() {
    return active;
  }
}
