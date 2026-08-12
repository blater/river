package io.riverdb.tx;

import io.riverdb.tx.api.Snapshot;

/** Transaction-owned reusable snapshot with a bounded active-transaction set. */
public final class TransactionSnapshot implements Snapshot {
  private final long[] activeTransactionIds;
  private long databaseHigh;
  private long databaseLow;
  private long snapshotSequence;
  private long visibleCommitSequence;
  private int activeTransactionCount;

  TransactionSnapshot(int maximumActiveTransactions) {
    activeTransactionIds = new long[maximumActiveTransactions];
  }

  @Override
  public long databaseIncarnationHigh() {
    return databaseHigh;
  }

  @Override
  public long databaseIncarnationLow() {
    return databaseLow;
  }

  @Override
  public long snapshotSequence() {
    return snapshotSequence;
  }

  @Override
  public long visibleCommitSequence() {
    return visibleCommitSequence;
  }

  @Override
  public int activeTransactionCount() {
    return activeTransactionCount;
  }

  @Override
  public long activeTransactionIdAt(int index) {
    return index >= 0 && index < activeTransactionCount ? activeTransactionIds[index] : 0;
  }

  void capture(
      long databaseIncarnationHigh,
      long databaseIncarnationLow,
      long sequence,
      long visibleSequence,
      long[] activeIds,
      int activeCount) {
    databaseHigh = databaseIncarnationHigh;
    databaseLow = databaseIncarnationLow;
    snapshotSequence = sequence;
    visibleCommitSequence = visibleSequence;
    activeTransactionCount = activeCount;
    for (int index = 0; index < activeCount; index++) {
      activeTransactionIds[index] = activeIds[index];
    }
  }
}
