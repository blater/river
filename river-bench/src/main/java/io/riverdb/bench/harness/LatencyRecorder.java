package io.riverdb.bench.harness;

import org.HdrHistogram.Histogram;

/** Fixed-footprint latency recorder with explicit open- and closed-loop accounting. */
public final class LatencyRecorder {
  private final DriverMode mode;
  private final long highestTrackableNanos;
  private final long expectedIntervalNanos;
  private final Histogram service;
  private final Histogram scheduled;
  private final Histogram correctedService;
  private long operationCount;

  public LatencyRecorder(
      DriverMode mode,
      long highestTrackableNanos,
      int significantDigits,
      long expectedIntervalNanos) {
    if (highestTrackableNanos < 1
        || significantDigits < 1
        || significantDigits > 5
        || (mode == DriverMode.OPEN_LOOP && expectedIntervalNanos < 1)
        || (mode == DriverMode.CLOSED_LOOP && expectedIntervalNanos != 0)) {
      throw new IllegalArgumentException("invalid latency recorder configuration");
    }
    this.mode = mode;
    this.highestTrackableNanos = highestTrackableNanos;
    this.expectedIntervalNanos = expectedIntervalNanos;
    service = new Histogram(highestTrackableNanos, significantDigits);
    scheduled = mode == DriverMode.OPEN_LOOP
        ? new Histogram(highestTrackableNanos, significantDigits) : null;
    correctedService = mode == DriverMode.OPEN_LOOP
        ? new Histogram(highestTrackableNanos, significantDigits) : null;
  }

  public LatencyRecordStatus record(
      long intendedStartNanos,
      long actualStartNanos,
      long completionNanos) {
    if (completionNanos < actualStartNanos
        || (mode == DriverMode.OPEN_LOOP && actualStartNanos < intendedStartNanos)) {
      return LatencyRecordStatus.INVALID_TIMESTAMPS;
    }
    long serviceNanos = completionNanos - actualStartNanos;
    long scheduledNanos = mode == DriverMode.OPEN_LOOP
        ? completionNanos - intendedStartNanos : serviceNanos;
    if (serviceNanos < 0
        || serviceNanos > highestTrackableNanos
        || scheduledNanos < 0
        || scheduledNanos > highestTrackableNanos) {
      return LatencyRecordStatus.OUT_OF_RANGE;
    }
    service.recordValue(serviceNanos);
    if (mode == DriverMode.OPEN_LOOP) {
      scheduled.recordValue(scheduledNanos);
      correctedService.recordValueWithExpectedInterval(serviceNanos, expectedIntervalNanos);
    }
    operationCount++;
    return LatencyRecordStatus.RECORDED;
  }

  public LatencyReport snapshot() {
    return new LatencyReport(
        mode,
        operationCount,
        expectedIntervalNanos,
        snapshot(service),
        scheduled == null ? null : snapshot(scheduled),
        correctedService == null ? null : snapshot(correctedService));
  }

  private static LatencySnapshot snapshot(Histogram histogram) {
    return new LatencySnapshot(
        histogram.getTotalCount(),
        histogram.getMinValue(),
        histogram.getValueAtPercentile(50.0),
        histogram.getValueAtPercentile(95.0),
        histogram.getValueAtPercentile(99.0),
        histogram.getValueAtPercentile(99.9),
        histogram.getMaxValue(),
        histogram.getMean());
  }
}
