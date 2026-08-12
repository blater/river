package io.riverdb.observability.api.event;

/**
 * Non-blocking structured-event boundary for correctness-critical kernel threads. Publication is
 * best effort and must never be used for security audit records.
 */
public interface KernelEventSink {
  boolean isEnabled(Severity severity);

  EventPublishResult publish(DiagnosticEvent event);
}
