package io.riverdb.bench.harness;

/** Outcome of a bounded streaming generation pass. */
public enum StreamingGenerationStatus {
  GENERATED,
  INVALID_SCRATCH_BUFFER,
  ROW_COUNT_MISMATCH,
  BYTE_COUNT_OVERFLOW
}
