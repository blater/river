package io.riverdb.tx;

import io.riverdb.base.error.StatusCode;

/** Byte-admitted diagnostic shape allocated once with a lock manager. */
public final class LockDeadlockDiagnosticsConfig {
  private static final int EPOCH_LONGS = 1;
  private static final int EPOCH_INTS = 2;
  private static final int SIGNATURE_LONGS = 9;
  private static final int SIGNATURE_INTS = 1;
  private static final int EVENT_LONGS = 10;
  private static final int EVENT_INTS = 3;
  private static final int EVENT_BYTES = 2;
  private static final int EXEMPLAR_INTS = 3;
  private static final int EDGE_LONGS = 18;
  private static final int EDGE_BYTES = 9;
  private static final int CYCLE_LONGS = 5;
  private static final int CYCLE_BYTES = 2;
  private static final LockDeadlockDiagnosticsConfig DISABLED =
      new LockDeadlockDiagnosticsConfig(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

  private final long maximumRetainedBytes;
  private final long retainedPayloadBytes;
  private final int maximumEpochs;
  private final int signaturesPerEpoch;
  private final int victimEventsPerEpoch;
  private final int exemplarsPerSignature;
  private final int maximumCycleEdges;
  private final int signatureCapacity;
  private final int victimEventCapacity;
  private final int exemplarCapacity;
  private final int edgeCapacity;

  private LockDeadlockDiagnosticsConfig(
      long requestedMaximumRetainedBytes,
      long requiredRetainedPayloadBytes,
      int requestedMaximumEpochs,
      int requestedSignaturesPerEpoch,
      int requestedVictimEventsPerEpoch,
      int requestedExemplarsPerSignature,
      int requestedMaximumCycleEdges,
      int signatures,
      int events,
      int exemplars,
      int edges) {
    maximumRetainedBytes = requestedMaximumRetainedBytes;
    retainedPayloadBytes = requiredRetainedPayloadBytes;
    maximumEpochs = requestedMaximumEpochs;
    signaturesPerEpoch = requestedSignaturesPerEpoch;
    victimEventsPerEpoch = requestedVictimEventsPerEpoch;
    exemplarsPerSignature = requestedExemplarsPerSignature;
    maximumCycleEdges = requestedMaximumCycleEdges;
    signatureCapacity = signatures;
    victimEventCapacity = events;
    exemplarCapacity = exemplars;
    edgeCapacity = edges;
  }

  public static LockDeadlockDiagnosticsConfig disabled() { return DISABLED; }

  /**
   * Admits a diagnostic shape against the caller's retained primitive-payload budget.
   *
   * <p>Every array dimension is limited only by Java array addressability. A shape that exceeds
   * addressability, arithmetic range, or the supplied budget returns {@code RESOURCE_EXHAUSTED}.
   * Zero exemplars is valid and retains aggregate/signature/event evidence without cycle copies.
   */
  public static StatusCode createBounded(
      long maximumRetainedBytes,
      int maximumEpochs,
      int signaturesPerEpoch,
      int victimEventsPerEpoch,
      int exemplarsPerSignature,
      int maximumCycleEdges,
      Result result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (maximumRetainedBytes <= 0 || maximumEpochs <= 0 || signaturesPerEpoch <= 0
        || victimEventsPerEpoch <= 0 || exemplarsPerSignature < 0
        || maximumCycleEdges < 2) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    try {
      long signatures = Math.multiplyExact((long) maximumEpochs, signaturesPerEpoch);
      long events = Math.multiplyExact((long) maximumEpochs, victimEventsPerEpoch);
      long exemplars = Math.multiplyExact(signatures, exemplarsPerSignature);
      long edges = Math.multiplyExact(exemplars, maximumCycleEdges);
      int signatureCapacity = addressable(signatures);
      int eventCapacity = addressable(events);
      int exemplarCapacity = addressable(exemplars);
      int edgeCapacity = addressable(edges);
      long required = retainedBytes(
          maximumEpochs, signatures, events, exemplars, edges, maximumCycleEdges);
      if (required > maximumRetainedBytes) return StatusCode.RESOURCE_EXHAUSTED;
      try {
        result.config = new LockDeadlockDiagnosticsConfig(
            maximumRetainedBytes, required, maximumEpochs, signaturesPerEpoch,
            victimEventsPerEpoch, exemplarsPerSignature, maximumCycleEdges,
            signatureCapacity, eventCapacity, exemplarCapacity, edgeCapacity);
      } catch (OutOfMemoryError failure) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      return StatusCode.OK;
    } catch (ArithmeticException failure) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  public boolean enabled() { return maximumRetainedBytes != 0; }
  public long maximumRetainedBytes() { return maximumRetainedBytes; }
  public long retainedPayloadBytes() { return retainedPayloadBytes; }
  public int maximumEpochs() { return maximumEpochs; }
  public int signaturesPerEpoch() { return signaturesPerEpoch; }
  public int victimEventsPerEpoch() { return victimEventsPerEpoch; }
  public int exemplarsPerSignature() { return exemplarsPerSignature; }
  public int maximumCycleEdges() { return maximumCycleEdges; }

  int signatureCapacity() { return signatureCapacity; }
  int victimEventCapacity() { return victimEventCapacity; }
  int exemplarCapacity() { return exemplarCapacity; }
  int edgeCapacity() { return edgeCapacity; }

  private static int addressable(long cells) {
    if (cells < 0 || cells > Integer.MAX_VALUE) throw new ArithmeticException("array length");
    return (int) cells;
  }

  private static long retainedBytes(
      long epochs, long signatures, long events, long exemplars, long edges,
      long cycleEdges) {
    long bytes = bytes(epochs, EPOCH_LONGS, EPOCH_INTS, 0);
    bytes = Math.addExact(bytes, bytes(signatures, SIGNATURE_LONGS, SIGNATURE_INTS, 0));
    bytes = Math.addExact(bytes, bytes(events, EVENT_LONGS, EVENT_INTS, EVENT_BYTES));
    bytes = Math.addExact(bytes, bytes(exemplars, 0, EXEMPLAR_INTS, 0));
    bytes = Math.addExact(bytes, bytes(edges, EDGE_LONGS, 0, EDGE_BYTES));
    return Math.addExact(bytes, bytes(cycleEdges, CYCLE_LONGS, 0, CYCLE_BYTES));
  }

  private static long bytes(long cells, int longs, int ints, int bytes) {
    long width = Math.addExact(
        Math.multiplyExact((long) longs, Long.BYTES),
        Math.addExact(Math.multiplyExact((long) ints, Integer.BYTES), bytes));
    return Math.multiplyExact(cells, width);
  }

  /** Caller-owned output so invalid or unadmitted configuration never publishes a config. */
  public static final class Result {
    private LockDeadlockDiagnosticsConfig config;

    public void reset() { config = null; }
    public LockDeadlockDiagnosticsConfig config() { return config; }
  }
}
