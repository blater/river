package io.riverdb.tx;

/** Signature aggregate columns owned by a diagnostic snapshot. */
public final class LockDeadlockSnapshotSignatures {
  private final LockDeadlockSnapshotCounters counters;
  final long[] epochs;
  final long[] fingerprints;
  final long[] collisionGuards;
  final long[] victims;
  final long[] outcomes;
  final long[] queuedCancelled;
  final long[] holdingsReleased;
  final long[] firstSequences;
  final long[] lastSequences;
  final int[] exemplars;

  LockDeadlockSnapshotSignatures(int capacity, LockDeadlockSnapshotCounters owner) {
    counters = owner;
    epochs = new long[capacity];
    fingerprints = new long[capacity];
    collisionGuards = new long[capacity];
    victims = new long[capacity];
    outcomes = new long[capacity];
    queuedCancelled = new long[capacity];
    holdingsReleased = new long[capacity];
    firstSequences = new long[capacity];
    lastSequences = new long[capacity];
    exemplars = new int[capacity];
  }

  public long epochAt(int index) { return epochs[checked(index)]; }
  public long fingerprintAt(int index) { return fingerprints[checked(index)]; }
  public long collisionGuardAt(int index) { return collisionGuards[checked(index)]; }
  public long victimSelectionsAt(int index) { return victims[checked(index)]; }
  public long victimOutcomesAt(int index) { return outcomes[checked(index)]; }
  public long queuedRequestsCancelledAt(int index) { return queuedCancelled[checked(index)]; }
  public long holdingsReleasedAt(int index) { return holdingsReleased[checked(index)]; }
  public long firstEventSequenceAt(int index) { return firstSequences[checked(index)]; }
  public long lastEventSequenceAt(int index) { return lastSequences[checked(index)]; }
  public int exemplarCountAt(int index) { return exemplars[checked(index)]; }

  void copyFrom(LockDeadlockSnapshotSignatures source) {
    copy(source.epochs, epochs);
    copy(source.fingerprints, fingerprints);
    copy(source.collisionGuards, collisionGuards);
    copy(source.victims, victims);
    copy(source.outcomes, outcomes);
    copy(source.queuedCancelled, queuedCancelled);
    copy(source.holdingsReleased, holdingsReleased);
    copy(source.firstSequences, firstSequences);
    copy(source.lastSequences, lastSequences);
    copy(source.exemplars, exemplars);
  }

  private static void copy(long[] source, long[] target) {
    System.arraycopy(source, 0, target, 0, source.length);
  }

  private static void copy(int[] source, int[] target) {
    System.arraycopy(source, 0, target, 0, source.length);
  }

  private int checked(int index) {
    if (index < 0 || index >= counters.signatureCount) {
      throw new IndexOutOfBoundsException(index);
    }
    return index;
  }
}
