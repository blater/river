package io.riverdb.tx;

import io.riverdb.base.error.StatusCode;

/** Primitive active-transaction snapshot floor and capture-set ownership. */
final class TransactionSnapshotRegistry {
  private final long[] transactionIds;
  private final long[] visibleCommitSequences;
  private int count;

  TransactionSnapshotRegistry(int capacity) {
    transactionIds = new long[capacity];
    visibleCommitSequences = new long[capacity];
  }

  int capacity() { return transactionIds.length; }
  int count() { return count; }
  long[] transactionIds() { return transactionIds; }
  boolean full() { return count == transactionIds.length; }

  StatusCode admit(long transactionId, long visibleCommitSequence) {
    if (transactionId <= 0 || visibleCommitSequence < 0 || full()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    transactionIds[count] = transactionId;
    visibleCommitSequences[count++] = visibleCommitSequence;
    return StatusCode.OK;
  }

  void update(long transactionId, long visibleCommitSequence) {
    for (int index = 0; index < count; index++) {
      if (transactionIds[index] == transactionId) {
        visibleCommitSequences[index] = visibleCommitSequence;
        return;
      }
    }
  }

  void remove(long transactionId) {
    for (int index = 0; index < count; index++) {
      if (transactionIds[index] != transactionId) continue;
      int moved = count - index - 1;
      if (moved > 0) {
        System.arraycopy(transactionIds, index + 1, transactionIds, index, moved);
        System.arraycopy(
            visibleCommitSequences, index + 1, visibleCommitSequences, index, moved);
      }
      transactionIds[--count] = 0;
      visibleCommitSequences[count] = 0;
      return;
    }
  }

  long oldestVisibleCommitSequence() {
    if (count == 0) return Long.MAX_VALUE;
    long oldest = visibleCommitSequences[0];
    for (int index = 1; index < count; index++) {
      oldest = Math.min(oldest, visibleCommitSequences[index]);
    }
    return oldest;
  }

}
