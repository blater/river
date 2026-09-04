package io.riverdb.wal.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import org.junit.jupiter.api.Test;

final class LocalWalMetricsTest {
  @Test
  void explicitCaptureDoesNotRetainEarlierLifetimeForces() {
    LocalWalForceMetrics metrics = new LocalWalForceMetrics();
    LocalWalMetrics captured = new LocalWalMetrics();
    LocalWalMetrics lifetime = new LocalWalMetrics();
    metrics.record(LocalWalForceCause.OTHER, 11, 13, StatusCode.OK);

    assertEquals(StatusCode.OK, metrics.beginCapture());
    metrics.record(LocalWalForceCause.DIRECT_COMMIT, 17, 19, StatusCode.OK);
    assertEquals(StatusCode.OK, metrics.endCapture(captured));
    metrics.copyTo(lifetime);

    assertTrue(captured.reconciles());
    assertEquals(1, captured.totalForceCount());
    assertEquals(17, captured.totalForceBytes());
    assertEquals(1, captured.forceCount(LocalWalForceCause.DIRECT_COMMIT));
    assertEquals(2, lifetime.totalForceCount());
    assertEquals(StatusCode.CONFLICT, metrics.endCapture(captured));
  }

  @Test
  void saturatedCounterInvalidatesTheSnapshot() {
    LocalWalMetrics metrics = new LocalWalMetrics();

    metrics.record(LocalWalForceCause.OTHER, Long.MAX_VALUE, 1, StatusCode.OK);
    metrics.record(LocalWalForceCause.OTHER, 1, 1, StatusCode.OK);

    assertFalse(metrics.reconciles());
    assertTrue(metrics.overflowed());
  }
}
