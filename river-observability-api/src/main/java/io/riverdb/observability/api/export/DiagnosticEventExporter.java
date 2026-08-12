package io.riverdb.observability.api.export;

import io.riverdb.observability.api.event.BoundedEventRing;
import io.riverdb.observability.api.event.DiagnosticContextField;
import io.riverdb.observability.api.event.DiagnosticEvent;
import io.riverdb.observability.api.event.EventPollResult;
import io.riverdb.observability.api.event.EventTypeId;
import io.riverdb.observability.api.event.Severity;
import io.riverdb.observability.api.redaction.DiagnosticRedactor;
import io.riverdb.observability.api.redaction.SensitiveFieldPolicies;
import io.riverdb.observability.api.redaction.SensitiveFieldPolicy;

/**
 * Single-consumer bridge from raw kernel events to an enforced sanitized exporter contract.
 * Security audit events must use a separate durable subsystem and never enter this bridge.
 */
public final class DiagnosticEventExporter {
  private final BoundedEventRing source;
  private final SanitizedEventView sanitizedView;
  private final DiagnosticEvent rawEvent = new DiagnosticEvent();

  /** Creates an exporter with the external-safe policy. */
  public DiagnosticEventExporter(BoundedEventRing source) {
    this(source, SensitiveFieldPolicies.safeExternal());
  }

  public static DiagnosticEventExporter safeExternal(BoundedEventRing source) {
    return new DiagnosticEventExporter(source);
  }

  /** Explicit privileged path for internal, access-controlled diagnostic consumers only. */
  public static DiagnosticEventExporter privilegedInternal(BoundedEventRing source) {
    return new DiagnosticEventExporter(source, SensitiveFieldPolicies.privileged());
  }

  private DiagnosticEventExporter(BoundedEventRing source, SensitiveFieldPolicy policy) {
    this.source = source;
    sanitizedView = new SanitizedEventView(policy);
  }

  public EventPollResult pollAndExport(SanitizedEventConsumer consumer) {
    EventPollResult result = source.poll(rawEvent);
    if (result == EventPollResult.POLLED) {
      sanitizedView.attach(rawEvent);
      consumer.accept(sanitizedView);
    }
    return result;
  }

  private static final class SanitizedEventView implements SanitizedDiagnosticEvent {
    private final SensitiveFieldPolicy policy;
    private DiagnosticEvent event;

    private SanitizedEventView(SensitiveFieldPolicy policy) {
      this.policy = policy;
    }

    private void attach(DiagnosticEvent newEvent) {
      event = newEvent;
    }

    @Override
    public EventTypeId type() {
      return event.type();
    }

    @Override
    public Severity severity() {
      return event.severity();
    }

    @Override
    public long monotonicNanos() {
      return event.monotonicNanos();
    }

    @Override
    public long sequence() {
      return event.sequence();
    }

    @Override
    public boolean hasContext(DiagnosticContextField field) {
      return DiagnosticRedactor.mayExportContext(event.context(), field, policy);
    }

    @Override
    public long contextValue(DiagnosticContextField field) {
      return DiagnosticRedactor.contextValue(event.context(), field, policy);
    }

    @Override
    public boolean hasEventField(int fieldIndex) {
      return DiagnosticRedactor.mayExportEventField(event, fieldIndex, policy);
    }

    @Override
    public long eventField(int fieldIndex) {
      return DiagnosticRedactor.eventField(event, fieldIndex, policy);
    }
  }
}
