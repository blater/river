package io.riverdb.protocol;

import java.nio.ByteBuffer;

/** Fixed-width opaque transaction correlation carried by execution requests. */
final class ProtocolTransactionDiagnosticContext {
  static final int BYTES = Long.BYTES * 3;

  private ProtocolTransactionDiagnosticContext() { }

  static boolean valid(long diagnosticTag, long diagnosticStepTag, long metricsEpoch) {
    return diagnosticTag == 0 && diagnosticStepTag == 0 && metricsEpoch == 0
        || diagnosticTag > 0 && diagnosticStepTag >= 0 && metricsEpoch > 0;
  }

  static int write(
      ByteBuffer target,
      int offset,
      long diagnosticTag,
      long diagnosticStepTag,
      long metricsEpoch) {
    target.putLong(offset, diagnosticTag);
    target.putLong(offset + Long.BYTES, diagnosticStepTag);
    target.putLong(offset + Long.BYTES * 2, metricsEpoch);
    return offset + BYTES;
  }
}
