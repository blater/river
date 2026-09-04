package io.riverdb.engine;

import io.riverdb.base.error.StatusCode;
import io.riverdb.tx.LockDeadlockDiagnosticsConfig;

/** Engine-boundary request for optional byte-admitted lock diagnostics. */
public final class EmbeddedLockDiagnosticsConfig {
  private static final EmbeddedLockDiagnosticsConfig DISABLED =
      new EmbeddedLockDiagnosticsConfig(0, 0, 0, 0, 0, 0);

  private final long maximumRetainedBytes;
  private final int maximumEpochs;
  private final int signaturesPerEpoch;
  private final int victimEventsPerEpoch;
  private final int exemplarsPerSignature;
  private final int maximumCycleEdges;

  private EmbeddedLockDiagnosticsConfig(
      long requestedMaximumRetainedBytes,
      int requestedMaximumEpochs,
      int requestedSignaturesPerEpoch,
      int requestedVictimEventsPerEpoch,
      int requestedExemplarsPerSignature,
      int requestedMaximumCycleEdges) {
    maximumRetainedBytes = requestedMaximumRetainedBytes;
    maximumEpochs = requestedMaximumEpochs;
    signaturesPerEpoch = requestedSignaturesPerEpoch;
    victimEventsPerEpoch = requestedVictimEventsPerEpoch;
    exemplarsPerSignature = requestedExemplarsPerSignature;
    maximumCycleEdges = requestedMaximumCycleEdges;
  }

  public static EmbeddedLockDiagnosticsConfig disabled() { return DISABLED; }

  /**
   * Describes requested storage without admitting it; database open owns validation and status.
   */
  public static EmbeddedLockDiagnosticsConfig bounded(
      long maximumRetainedBytes,
      int maximumEpochs,
      int signaturesPerEpoch,
      int victimEventsPerEpoch,
      int exemplarsPerSignature,
      int maximumCycleEdges) {
    return new EmbeddedLockDiagnosticsConfig(
        maximumRetainedBytes, maximumEpochs, signaturesPerEpoch,
        victimEventsPerEpoch, exemplarsPerSignature, maximumCycleEdges);
  }

  public long maximumRetainedBytes() { return maximumRetainedBytes; }
  public int maximumEpochs() { return maximumEpochs; }
  public int signaturesPerEpoch() { return signaturesPerEpoch; }
  public int victimEventsPerEpoch() { return victimEventsPerEpoch; }
  public int exemplarsPerSignature() { return exemplarsPerSignature; }
  public int maximumCycleEdges() { return maximumCycleEdges; }

  StatusCode admit(LockDeadlockDiagnosticsConfig.Result result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (maximumRetainedBytes == 0) {
      result.reset();
      return StatusCode.OK;
    }
    return LockDeadlockDiagnosticsConfig.createBounded(
        maximumRetainedBytes, maximumEpochs, signaturesPerEpoch,
        victimEventsPerEpoch, exemplarsPerSignature, maximumCycleEdges, result);
  }
}
