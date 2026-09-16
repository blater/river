package io.riverdb.tx;

import io.riverdb.base.error.StatusCode;

/** Victim event columns owned by a diagnostic snapshot. */
public final class LockDeadlockSnapshotEvents {
  private static final StatusCode[] STATUS_CODES = StatusCode.values();
  private final LockDeadlockSnapshotCounters counters;
  final long[] epochs;
  final long[] sequences;
  final long[] outcomeSequences;
  final long[] victimSequences;
  final long[] fingerprints;
  final long[] transactionIds;
  final long[] transactionGenerations;
  final long[] startOrders;
  final long[] diagnosticTags;
  final long[] diagnosticStepTags;
  final int[] signatureIndexes;
  final int[] queuedCancelled;
  final int[] holdingsReleased;
  final byte[] cleanupValid;
  final byte[] outcomeStatuses;

  LockDeadlockSnapshotEvents(int capacity, LockDeadlockSnapshotCounters owner) {
    counters = owner;
    epochs = new long[capacity];
    sequences = new long[capacity];
    outcomeSequences = new long[capacity];
    victimSequences = new long[capacity];
    fingerprints = new long[capacity];
    transactionIds = new long[capacity];
    transactionGenerations = new long[capacity];
    startOrders = new long[capacity];
    diagnosticTags = new long[capacity];
    diagnosticStepTags = new long[capacity];
    signatureIndexes = new int[capacity];
    queuedCancelled = new int[capacity];
    holdingsReleased = new int[capacity];
    cleanupValid = new byte[capacity];
    outcomeStatuses = new byte[capacity];
  }

  public long epochAt(int index) { return epochs[checked(index)]; }
  public long sequenceAt(int index) { return sequences[checked(index)]; }
  public long outcomeSequenceAt(int index) { return outcomeSequences[checked(index)]; }
  public long victimSelectionSequenceAt(int index) { return victimSequences[checked(index)]; }
  public long fingerprintAt(int index) { return fingerprints[checked(index)]; }
  public long transactionIdAt(int index) { return transactionIds[checked(index)]; }
  public long transactionGenerationAt(int index) { return transactionGenerations[checked(index)]; }
  public long transactionStartOrderAt(int index) { return startOrders[checked(index)]; }
  public long diagnosticTagAt(int index) { return diagnosticTags[checked(index)]; }
  public long diagnosticStepTagAt(int index) { return diagnosticStepTags[checked(index)]; }
  public int signatureIndexAt(int index) { return signatureIndexes[checked(index)]; }
  public int queuedRequestsCancelledAt(int index) { return queuedCancelled[checked(index)]; }
  public int holdingsReleasedAt(int index) { return holdingsReleased[checked(index)]; }
  public boolean cleanupValidAt(int index) { return cleanupValid[checked(index)] != 0; }
  public StatusCode outcomeStatusAt(int index) {
    int ordinal = Byte.toUnsignedInt(outcomeStatuses[checked(index)]) - 1;
    return ordinal < 0 ? null : STATUS_CODES[ordinal];
  }

  void copyFrom(LockDeadlockSnapshotEvents source) {
    copy(source.epochs, epochs);
    copy(source.sequences, sequences);
    copy(source.outcomeSequences, outcomeSequences);
    copy(source.victimSequences, victimSequences);
    copy(source.fingerprints, fingerprints);
    copy(source.transactionIds, transactionIds);
    copy(source.transactionGenerations, transactionGenerations);
    copy(source.startOrders, startOrders);
    copy(source.diagnosticTags, diagnosticTags);
    copy(source.diagnosticStepTags, diagnosticStepTags);
    copy(source.signatureIndexes, signatureIndexes);
    copy(source.queuedCancelled, queuedCancelled);
    copy(source.holdingsReleased, holdingsReleased);
    copy(source.cleanupValid, cleanupValid);
    copy(source.outcomeStatuses, outcomeStatuses);
  }

  private static void copy(long[] source, long[] target) {
    System.arraycopy(source, 0, target, 0, source.length);
  }

  private static void copy(int[] source, int[] target) {
    System.arraycopy(source, 0, target, 0, source.length);
  }

  private static void copy(byte[] source, byte[] target) {
    System.arraycopy(source, 0, target, 0, source.length);
  }

  private int checked(int index) {
    if (index < 0 || index >= counters.victimEventCount) {
      throw new IndexOutOfBoundsException(index);
    }
    return index;
  }
}
