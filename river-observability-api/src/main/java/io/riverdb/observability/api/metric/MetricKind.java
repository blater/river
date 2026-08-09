package io.riverdb.observability.api.metric;

/** Metric update semantics fixed by the metric registry. */
public enum MetricKind {
  COUNTER,
  GAUGE,
  HISTOGRAM
}
