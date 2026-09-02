package io.riverdb.engine.table;

import io.riverdb.storage.heap.HeapRowResult;
import io.riverdb.base.error.StatusCode;
import io.riverdb.tx.api.lock.LockToken;

/** Caller-owned current row capability whose exclusive key lock is held by its transaction. */
public final class IndexedLockedRow {
  private final HeapRowResult row = new HeapRowResult();
  private final LockToken lock = new LockToken();
  private IndexedTransactionSession owner;
  private long transactionGeneration;
  private long keySpace;
  private long key;
  private long candidateVersionRowId;
  private long currentVersionRowId;
  private int pendingIndex = -1;
  private boolean consumed;

  public HeapRowResult row() { return row; }

  public long keySpace() { return keySpace; }

  public long key() { return key; }

  public long candidateVersionRowId() { return candidateVersionRowId; }

  public long currentVersionRowId() { return currentVersionRowId; }

  public boolean isAvailable() { return owner != null && !consumed; }

  public StatusCode reset() {
    if (lock.isActive()) return StatusCode.CONFLICT;
    row.reset();
    owner = null;
    transactionGeneration = 0;
    keySpace = 0;
    key = 0;
    candidateVersionRowId = 0;
    currentVersionRowId = 0;
    pendingIndex = -1;
    consumed = false;
    return lock.reset();
  }

  void set(
      IndexedTransactionSession session, long generation,
      long space, long value, long candidateRowId, long currentRowId, int currentPendingIndex) {
    owner = session;
    transactionGeneration = generation;
    keySpace = space;
    key = value;
    candidateVersionRowId = candidateRowId;
    currentVersionRowId = currentRowId;
    pendingIndex = currentPendingIndex;
    consumed = false;
  }

  boolean isOwnedBy(IndexedTransactionSession session, long generation) {
    return owner == session && transactionGeneration == generation && !consumed;
  }

  int pendingIndex() { return pendingIndex; }

  LockToken lock() { return lock; }

  void consume() {
    consumed = true;
    row.reset();
  }
}
