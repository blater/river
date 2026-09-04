package io.riverdb.engine.table;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.riverdb.base.error.StatusCode;
import org.junit.jupiter.api.Test;

final class IndexedGroupCommitTelemetryTest {
  @Test
  void cohortHistogramCoversTheFullPositiveIntRange() {
    IndexedGroupCommitTelemetry telemetry = new IndexedGroupCommitTelemetry();

    telemetry.recordSuccessfulGroup(1);
    telemetry.recordSuccessfulGroup(17);
    telemetry.recordSuccessfulGroup(Integer.MAX_VALUE);

    assertEquals(1, telemetry.successfulCohortSizeBucket(0));
    assertEquals(1, telemetry.successfulCohortSizeBucket(4));
    assertEquals(
        1,
        telemetry.successfulCohortSizeBucket(
            IndexedGroupCommitTelemetry.COHORT_SIZE_BUCKETS - 1));
    assertEquals(Integer.MAX_VALUE, telemetry.maximumSuccessfulCohort());
    assertEquals(
        Integer.MAX_VALUE,
        IndexedGroupCommitTelemetry.cohortSizeUpperBound(
            IndexedGroupCommitTelemetry.COHORT_SIZE_BUCKETS - 1));
  }

  @Test
  void explicitCaptureDoesNotRetainEarlierLifetimeEvents() {
    IndexedGroupCommitMetrics metrics = new IndexedGroupCommitMetrics();
    IndexedGroupCommitTelemetry captured = new IndexedGroupCommitTelemetry();
    IndexedGroupCommitTelemetry lifetime = new IndexedGroupCommitTelemetry();
    metrics.recordReadOnlyCommit();

    assertEquals(StatusCode.OK, metrics.beginCapture());
    metrics.recordReadOnlyCommit();
    assertEquals(StatusCode.OK, metrics.endCapture(captured));
    metrics.copyTo(lifetime);

    assertEquals(1, captured.totalCommitSubmissions());
    assertEquals(1, captured.readOnlyCommitSubmissions());
    assertEquals(2, lifetime.totalCommitSubmissions());
    assertEquals(StatusCode.CONFLICT, metrics.endCapture(captured));
  }

  @Test
  void saturatedCounterInvalidatesTheSnapshot() {
    IndexedGroupCommitTelemetry telemetry = new IndexedGroupCommitTelemetry();

    telemetry.recordStage(
        IndexedCommitPath.SHARED_GROUP, IndexedCommitStage.GROUP_FORCE, Long.MAX_VALUE);
    telemetry.recordStage(
        IndexedCommitPath.SHARED_GROUP, IndexedCommitStage.GROUP_FORCE, 1);

    assertFalse(telemetry.reconciles());
    assertEquals(true, telemetry.overflowed());
  }
}
