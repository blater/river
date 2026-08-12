package io.riverdb.bench.harness;

/** Latency views for one driver mode. Null optional views are unavailable by contract. */
public record LatencyReport(
    DriverMode mode,
    long operationCount,
    long expectedIntervalNanos,
    LatencySnapshot service,
    LatencySnapshot scheduled,
    LatencySnapshot coordinatedOmissionCorrectedService) {
}
