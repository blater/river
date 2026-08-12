package io.riverdb.bench.harness;

/** Immutable histogram summary; the encoded histogram remains a separate raw artifact later. */
public record LatencySnapshot(
    long count,
    long minimumNanos,
    long p50Nanos,
    long p95Nanos,
    long p99Nanos,
    long p999Nanos,
    long maximumNanos,
    double meanNanos) {
}
