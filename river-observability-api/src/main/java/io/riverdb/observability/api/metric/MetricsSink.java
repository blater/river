package io.riverdb.observability.api.metric;

/**
 * Bounded-cardinality primitive metric boundary. Names imply their update kind; labels and dynamic
 * names are deliberately absent.
 */
public interface MetricsSink {
  boolean isEnabled(MetricName name);

  void addCounter(MetricName name, long delta);

  void setGauge(MetricName name, long value);

  void recordHistogram(MetricName name, long value);
}
