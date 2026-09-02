package io.riverdb.tx;

/** Fixed diagnostic capacities allocated once with a lock manager. */
public final class LockDeadlockDiagnosticsConfig {
  private static final long MAXIMUM_RETAINED_CELLS = 16L << 20;
  private static final LockDeadlockDiagnosticsConfig DISABLED =
      new LockDeadlockDiagnosticsConfig(0, 0, 0, 0, 0, false);

  private final int maximumEpochs;
  private final int signaturesPerEpoch;
  private final int victimEventsPerEpoch;
  private final int exemplarsPerSignature;
  private final int maximumCycleEdges;

  private LockDeadlockDiagnosticsConfig(
      int maximumEpochs,
      int signaturesPerEpoch,
      int victimEventsPerEpoch,
      int exemplarsPerSignature,
      int maximumCycleEdges,
      boolean validate) {
    if (validate) validate(maximumEpochs, signaturesPerEpoch, victimEventsPerEpoch,
        exemplarsPerSignature, maximumCycleEdges);
    this.maximumEpochs = maximumEpochs;
    this.signaturesPerEpoch = signaturesPerEpoch;
    this.victimEventsPerEpoch = victimEventsPerEpoch;
    this.exemplarsPerSignature = exemplarsPerSignature;
    this.maximumCycleEdges = maximumCycleEdges;
  }

  public static LockDeadlockDiagnosticsConfig disabled() { return DISABLED; }

  /**
   * Enables fixed epoch partitions. Exhaustion is reported and never overwrites prior evidence.
   */
  public static LockDeadlockDiagnosticsConfig bounded(
      int maximumEpochs,
      int signaturesPerEpoch,
      int victimEventsPerEpoch,
      int exemplarsPerSignature,
      int maximumCycleEdges) {
    return new LockDeadlockDiagnosticsConfig(maximumEpochs, signaturesPerEpoch,
        victimEventsPerEpoch, exemplarsPerSignature, maximumCycleEdges, true);
  }

  public boolean enabled() { return maximumEpochs != 0; }
  public int maximumEpochs() { return maximumEpochs; }
  public int signaturesPerEpoch() { return signaturesPerEpoch; }
  public int victimEventsPerEpoch() { return victimEventsPerEpoch; }
  public int exemplarsPerSignature() { return exemplarsPerSignature; }
  public int maximumCycleEdges() { return maximumCycleEdges; }

  int signatureCapacity() { return Math.multiplyExact(maximumEpochs, signaturesPerEpoch); }
  int victimEventCapacity() { return Math.multiplyExact(maximumEpochs, victimEventsPerEpoch); }
  int exemplarCapacity() { return Math.multiplyExact(signatureCapacity(), exemplarsPerSignature); }
  int edgeCapacity() { return Math.multiplyExact(exemplarCapacity(), maximumCycleEdges); }

  private static void validate(
      int epochs, int signatures, int events, int exemplars, int edges) {
    if (epochs <= 0 || signatures <= 0 || events <= 0 || exemplars <= 0 || edges < 2) {
      throw new IllegalArgumentException("diagnostic capacities must be positive");
    }
    try {
      long signatureCells = Math.multiplyExact((long) epochs, signatures);
      long eventCells = Math.multiplyExact((long) epochs, events);
      long exemplarCells = Math.multiplyExact(signatureCells, exemplars);
      long edgeCells = Math.multiplyExact(exemplarCells, edges);
      long retainedCells = Math.addExact(Math.addExact(signatureCells, eventCells), edgeCells);
      if (signatureCells > Integer.MAX_VALUE || eventCells > Integer.MAX_VALUE
          || exemplarCells > Integer.MAX_VALUE || edgeCells > Integer.MAX_VALUE
          || retainedCells > MAXIMUM_RETAINED_CELLS) {
        throw new IllegalArgumentException("diagnostic capacities exceed bounded limit");
      }
    } catch (ArithmeticException overflow) {
      throw new IllegalArgumentException("diagnostic capacities overflow", overflow);
    }
  }
}
