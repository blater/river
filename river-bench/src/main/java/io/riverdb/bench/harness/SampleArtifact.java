package io.riverdb.bench.harness;

/** One schema-validated row in the immutable sample table. */
public record SampleArtifact(
    String workload,
    String mode,
    String metric,
    long operationCount,
    long expectedIntervalNanos,
    LatencySnapshot latency) {
}
