package io.riverdb.testkit.tx;

import io.riverdb.tx.api.Snapshot;
import java.util.Arrays;

/** Immutable deterministic snapshot that owns a copy of its sorted active-transaction array. */
public final class DeterministicSnapshot implements Snapshot {
  private final long databaseIncarnationHigh;
  private final long databaseIncarnationLow;
  private final long sequence;
  private final long visibleCommitSequence;
  private final long[] activeTransactions;
  private final int activeTransactionCount;

  public DeterministicSnapshot(
      long databaseHigh,
      long databaseLow,
      long snapshotSequence,
      long visibleCsn,
      long[] sortedActiveTransactions,
      int activeCount) {
    databaseIncarnationHigh = databaseHigh;
    databaseIncarnationLow = databaseLow;
    sequence = snapshotSequence;
    visibleCommitSequence = visibleCsn;
    activeTransactions = Arrays.copyOf(sortedActiveTransactions, activeCount);
    activeTransactionCount = activeCount;
  }

  @Override
  public long databaseIncarnationHigh() {
    return databaseIncarnationHigh;
  }

  @Override
  public long databaseIncarnationLow() {
    return databaseIncarnationLow;
  }

  @Override
  public long snapshotSequence() {
    return sequence;
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
    return activeTransactions[index];
  }
}
