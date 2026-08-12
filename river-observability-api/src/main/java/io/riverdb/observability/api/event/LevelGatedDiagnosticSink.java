package io.riverdb.observability.api.event;

/** Mutable level gate configured outside the event hot path. */
public final class LevelGatedDiagnosticSink implements DiagnosticSink {
  private final KernelEventSink delegate;
  private volatile Severity threshold;

  public LevelGatedDiagnosticSink(KernelEventSink delegate, Severity initialThreshold) {
    this.delegate = delegate;
    threshold = initialThreshold;
  }

  public void threshold(Severity newThreshold) {
    threshold = newThreshold;
  }

  public Severity threshold() {
    return threshold;
  }

  @Override
  public boolean isEnabled(Severity severity) {
    return severity.isEnabledAt(threshold) && delegate.isEnabled(severity);
  }

  @Override
  public EventPublishResult publish(DiagnosticEvent event) {
    if (!event.severity().isEnabledAt(threshold)) {
      return EventPublishResult.DISABLED;
    }
    return delegate.publish(event);
  }
}
