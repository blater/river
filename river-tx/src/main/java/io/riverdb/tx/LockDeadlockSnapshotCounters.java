package io.riverdb.tx;

/** Counters and epoch indexes owned by a diagnostic snapshot. */
public final class LockDeadlockSnapshotCounters {
  final long[] epochs;
  final int[] epochSignatureCounts;
  final int[] epochVictimEventCounts;
  long totalVictimSelections;
  long victimTransactionOutcomes;
  long queuedRequestsCancelled;
  long holdingsReleased;
  long selfValidationFailures;
  long fingerprintOverflows;
  long fingerprintCollisions;
  long epochOverflows;
  long victimEventOverflows;
  long exemplarOverflows;
  long cycleEdgeOverflows;
  long eventSequenceOverflows;
  long lastValidationFailureSequence;
  long lastValidationFailureEpoch;
  int epochCount;
  int signatureCount;
  int victimEventCount;
  int exemplarCount;

  LockDeadlockSnapshotCounters(int capacity) {
    epochs = new long[capacity];
    epochSignatureCounts = new int[capacity];
    epochVictimEventCounts = new int[capacity];
  }

  public int epochCount() { return epochCount; }
  public long epochAt(int index) { return epochs[checked(index, epochCount)]; }
  public long totalVictimSelections() { return totalVictimSelections; }
  public long victimTransactionOutcomes() { return victimTransactionOutcomes; }
  public long queuedRequestsCancelled() { return queuedRequestsCancelled; }
  public long holdingsReleased() { return holdingsReleased; }
  public long selfValidationFailures() { return selfValidationFailures; }
  public long fingerprintOverflows() { return fingerprintOverflows; }
  public long fingerprintCollisions() { return fingerprintCollisions; }
  public long epochOverflows() { return epochOverflows; }
  public long victimEventOverflows() { return victimEventOverflows; }
  public long exemplarOverflows() { return exemplarOverflows; }
  public long cycleEdgeOverflows() { return cycleEdgeOverflows; }
  public long eventSequenceOverflows() { return eventSequenceOverflows; }
  public long lastValidationFailureSequence() { return lastValidationFailureSequence; }
  public long lastValidationFailureEpoch() { return lastValidationFailureEpoch; }
  public int signatureCount() { return signatureCount; }
  public int victimEventCount() { return victimEventCount; }
  public int exemplarCount() { return exemplarCount; }

  void copyFrom(LockDeadlockSnapshotCounters source) {
    totalVictimSelections = source.totalVictimSelections;
    victimTransactionOutcomes = source.victimTransactionOutcomes;
    queuedRequestsCancelled = source.queuedRequestsCancelled;
    holdingsReleased = source.holdingsReleased;
    selfValidationFailures = source.selfValidationFailures;
    fingerprintOverflows = source.fingerprintOverflows;
    fingerprintCollisions = source.fingerprintCollisions;
    epochOverflows = source.epochOverflows;
    victimEventOverflows = source.victimEventOverflows;
    exemplarOverflows = source.exemplarOverflows;
    cycleEdgeOverflows = source.cycleEdgeOverflows;
    eventSequenceOverflows = source.eventSequenceOverflows;
    lastValidationFailureSequence = source.lastValidationFailureSequence;
    lastValidationFailureEpoch = source.lastValidationFailureEpoch;
    epochCount = source.epochCount;
    signatureCount = source.signatureCount;
    victimEventCount = source.victimEventCount;
    exemplarCount = source.exemplarCount;
    copy(source.epochs, epochs);
    copy(source.epochSignatureCounts, epochSignatureCounts);
    copy(source.epochVictimEventCounts, epochVictimEventCounts);
  }

  private static void copy(long[] source, long[] target) {
    System.arraycopy(source, 0, target, 0, source.length);
  }

  private static void copy(int[] source, int[] target) {
    System.arraycopy(source, 0, target, 0, source.length);
  }

  private static int checked(int index, int count) {
    if (index < 0 || index >= count) throw new IndexOutOfBoundsException(index);
    return index;
  }
}
