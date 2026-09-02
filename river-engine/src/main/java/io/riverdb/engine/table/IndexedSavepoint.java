package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;

/** Caller-owned authenticated position in one indexed transaction's pending write set. */
public final class IndexedSavepoint {
  private IndexedTransactionSession owner;
  private long transactionId;
  private int pendingMutationCount;
  private int tupleMutationCount;
  private int tupleDescriptorCount;
  private int tuplePayloadBytes;
  private int tupleLifecycleCount;
  private boolean active;

  public StatusCode reset() {
    if (active) {
      return StatusCode.CONFLICT;
    }
    owner = null;
    transactionId = 0;
    pendingMutationCount = 0;
    tupleMutationCount = 0;
    tupleDescriptorCount = 0;
    tuplePayloadBytes = 0;
    tupleLifecycleCount = 0;
    return StatusCode.OK;
  }

  StatusCode claim(
      IndexedTransactionSession session,
      long ownerTransactionId,
      int mutations,
      int tupleMutations,
      int tupleDescriptors,
      int tupleBytes,
      int lifecycleCount) {
    if (active) {
      return StatusCode.CONFLICT;
    }
    owner = session;
    transactionId = ownerTransactionId;
    pendingMutationCount = mutations;
    tupleMutationCount = tupleMutations;
    tupleDescriptorCount = tupleDescriptors;
    tuplePayloadBytes = tupleBytes;
    tupleLifecycleCount = lifecycleCount;
    active = true;
    return StatusCode.OK;
  }

  boolean isOwnedBy(IndexedTransactionSession session, long ownerTransactionId) {
    return active && owner == session && transactionId == ownerTransactionId;
  }

  int pendingMutationCount() {
    return pendingMutationCount;
  }

  int tupleMutationCount() { return tupleMutationCount; }
  int tupleDescriptorCount() { return tupleDescriptorCount; }
  int tuplePayloadBytes() { return tuplePayloadBytes; }
  int tupleLifecycleCount() { return tupleLifecycleCount; }

  void complete() {
    active = false;
  }

  public boolean isActive() {
    return active;
  }
}
