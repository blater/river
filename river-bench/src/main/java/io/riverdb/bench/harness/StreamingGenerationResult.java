package io.riverdb.bench.harness;

/** Primitive generation counters returned without per-row result allocation. */
public record StreamingGenerationResult(
    StreamingGenerationStatus status,
    long rowCount,
    long byteCount) {
}
