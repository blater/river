package io.riverdb.bench.harness;

/** Non-throwing outcome for one latency observation. */
public enum LatencyRecordStatus {
  RECORDED,
  INVALID_TIMESTAMPS,
  OUT_OF_RANGE
}
