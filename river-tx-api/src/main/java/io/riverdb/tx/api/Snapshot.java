package io.riverdb.tx.api;

/**
 * Immutable, provider-owned visibility boundary borrowed for the lifetime documented by the
 * transaction implementation. Active transaction identifiers are exposed without collection or
 * iterator allocation and must be in ascending order.
 */
public interface Snapshot {
  long databaseIncarnationHigh();

  long databaseIncarnationLow();

  long snapshotSequence();

  long visibleCommitSequence();

  int activeTransactionCount();

  long activeTransactionIdAt(int index);

  /** Whether the owner was active when this boundary was captured. */
  default boolean excludesTransaction(long transactionId) {
    int low = 0;
    int high = activeTransactionCount() - 1;
    while (low <= high) {
      int middle = (low + high) >>> 1;
      long candidate = activeTransactionIdAt(middle);
      if (candidate < transactionId) {
        low = middle + 1;
      } else if (candidate > transactionId) {
        high = middle - 1;
      } else {
        return true;
      }
    }
    return false;
  }
}
