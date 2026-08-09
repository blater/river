package io.riverdb.observability.api.export;

import io.riverdb.observability.api.event.DiagnosticContextField;
import io.riverdb.observability.api.event.EventTypeId;
import io.riverdb.observability.api.event.Severity;

/**
 * Exporter-facing event view which exposes payload values only after applying a central sensitive
 * field policy. Implementations are reusable views and must not be retained by a consumer.
 */
public interface SanitizedDiagnosticEvent {
  EventTypeId type();

  Severity severity();

  long monotonicNanos();

  long sequence();

  boolean hasContext(DiagnosticContextField field);

  long contextValue(DiagnosticContextField field);

  boolean hasEventField(int fieldIndex);

  long eventField(int fieldIndex);
}
