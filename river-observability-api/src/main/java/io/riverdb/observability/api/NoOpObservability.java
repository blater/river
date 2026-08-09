package io.riverdb.observability.api;

import io.riverdb.observability.api.event.DiagnosticEvent;
import io.riverdb.observability.api.event.DiagnosticSink;
import io.riverdb.observability.api.event.EventPublishResult;
import io.riverdb.observability.api.event.KernelEventSink;
import io.riverdb.observability.api.event.Severity;
import io.riverdb.observability.api.metric.MetricName;
import io.riverdb.observability.api.metric.MetricsSink;

/** Allocation-free singleton used when all observability is disabled. */
public final class NoOpObservability implements DiagnosticSink, MetricsSink {
  private static final NoOpObservability INSTANCE = new NoOpObservability();

  private NoOpObservability() {
  }

  public static NoOpObservability instance() {
    return INSTANCE;
  }

  public static DiagnosticSink diagnosticSink() {
    return INSTANCE;
  }

  public static KernelEventSink kernelEventSink() {
    return INSTANCE;
  }

  public static MetricsSink metricsSink() {
    return INSTANCE;
  }

  @Override
  public boolean isEnabled(Severity severity) {
    return false;
  }

  @Override
  public EventPublishResult publish(DiagnosticEvent event) {
    return EventPublishResult.DISABLED;
  }

  @Override
  public boolean isEnabled(MetricName name) {
    return false;
  }

  @Override
  public void addCounter(MetricName name, long delta) {
  }

  @Override
  public void setGauge(MetricName name, long value) {
  }

  @Override
  public void recordHistogram(MetricName name, long value) {
  }
}
