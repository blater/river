package io.riverdb.observability.api.metric;

/** Stable, bounded-cardinality metric registry independent of an exporter. */
public enum MetricName {
  UNKNOWN(0, "river.unknown", MetricKind.GAUGE, "1"),
  DIAGNOSTIC_EVENTS_PUBLISHED_TOTAL(
      1000, "river.diagnostic.events.published.total", MetricKind.COUNTER, "events"),
  DIAGNOSTIC_EVENTS_DROPPED_TOTAL(
      1001, "river.diagnostic.events.dropped.total", MetricKind.COUNTER, "events"),
  DIAGNOSTIC_BACKPRESSURE_TOTAL(
      1003, "river.diagnostic.backpressure.total", MetricKind.COUNTER, "events"),
  DIAGNOSTIC_QUEUE_DEPTH(
      1004, "river.diagnostic.queue.depth", MetricKind.GAUGE, "events"),
  WAL_FORCE_LATENCY_NANOS(
      2000, "river.wal.force.latency", MetricKind.HISTOGRAM, "nanoseconds");

  private static final MetricName[] REGISTRY = values();

  private final int stableId;
  private final String canonicalName;
  private final MetricKind kind;
  private final String unit;

  MetricName(int stableId, String canonicalName, MetricKind kind, String unit) {
    this.stableId = stableId;
    this.canonicalName = canonicalName;
    this.kind = kind;
    this.unit = unit;
  }

  public int stableId() {
    return stableId;
  }

  public String canonicalName() {
    return canonicalName;
  }

  public MetricKind kind() {
    return kind;
  }

  public String unit() {
    return unit;
  }

  public static MetricName fromStableId(int stableId) {
    for (MetricName candidate : REGISTRY) {
      if (candidate.stableId == stableId) {
        return candidate;
      }
    }
    return UNKNOWN;
  }
}
