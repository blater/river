package io.riverdb.bench.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class LatencyRecorderTest {
  @Test
  void closedLoopRecordsServiceOnly() {
    LatencyRecorder recorder = new LatencyRecorder(DriverMode.CLOSED_LOOP, 1_000_000, 3, 0);

    assertEquals(LatencyRecordStatus.RECORDED, recorder.record(-500_000, 0, 10_000));
    assertEquals(LatencyRecordStatus.RECORDED, recorder.record(20_000, 20_000, 70_000));
    LatencyReport report = recorder.snapshot();

    assertEquals(2, report.operationCount());
    assertEquals(2, report.service().count());
    assertNull(report.scheduled());
    assertNull(report.coordinatedOmissionCorrectedService());
  }

  @Test
  void openLoopSeparatesQueueDelayAndCorrectsServiceOmission() {
    LatencyRecorder recorder = new LatencyRecorder(DriverMode.OPEN_LOOP, 1_000_000, 3, 100_000);

    assertEquals(LatencyRecordStatus.RECORDED, recorder.record(0, 200_000, 650_000));
    LatencyReport report = recorder.snapshot();

    assertEquals(1, report.operationCount());
    assertEquals(1, report.service().count());
    assertEquals(1, report.scheduled().count());
    assertTrue(report.scheduled().maximumNanos() > report.service().maximumNanos());
    assertTrue(report.coordinatedOmissionCorrectedService().count() > report.service().count());
  }

  @Test
  void invalidAndOutOfRangeSamplesDoNotPolluteHistograms() {
    LatencyRecorder recorder = new LatencyRecorder(DriverMode.OPEN_LOOP, 100_000, 3, 10_000);

    assertEquals(LatencyRecordStatus.INVALID_TIMESTAMPS, recorder.record(20, 10, 30));
    assertEquals(LatencyRecordStatus.INVALID_TIMESTAMPS, recorder.record(0, 50, 40));
    assertEquals(LatencyRecordStatus.OUT_OF_RANGE, recorder.record(0, 0, 100_001));
    assertEquals(0, recorder.snapshot().operationCount());
    assertEquals(0, recorder.snapshot().service().count());
  }
}
