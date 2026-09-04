package io.riverdb.wal.local;

import io.riverdb.base.error.StatusCode;

/** Single-owner counters for every physical force issued by one local WAL. */
final class LocalWalForceMetrics {
  private final LocalWalMetrics values = new LocalWalMetrics();
  private final LocalWalMetrics capture = new LocalWalMetrics();
  private boolean capturing;

  synchronized void record(
      LocalWalForceCause cause,
      long coveredBytes,
      long elapsedNanos,
      StatusCode status) {
    values.record(cause, coveredBytes, elapsedNanos, status);
    if (capturing) capture.record(cause, coveredBytes, elapsedNanos, status);
  }

  synchronized void copyTo(LocalWalMetrics target) {
    target.copyFrom(values);
  }

  synchronized StatusCode beginCapture() {
    if (capturing) return StatusCode.CONFLICT;
    capture.reset();
    capturing = true;
    return StatusCode.OK;
  }

  synchronized StatusCode endCapture(LocalWalMetrics target) {
    if (!capturing || target == null) return StatusCode.CONFLICT;
    capturing = false;
    target.copyFrom(capture);
    return StatusCode.OK;
  }

  synchronized StatusCode cancelCapture() {
    if (!capturing) return StatusCode.CONFLICT;
    capturing = false;
    capture.reset();
    return StatusCode.OK;
  }

  void merge(LocalWalForceMetrics source) {
    LocalWalMetrics merged = new LocalWalMetrics();
    source.copyTo(merged);
    synchronized (this) {
      values.merge(merged);
      if (capturing) capture.merge(merged);
    }
  }
}
