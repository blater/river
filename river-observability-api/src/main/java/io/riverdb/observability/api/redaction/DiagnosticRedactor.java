package io.riverdb.observability.api.redaction;

import io.riverdb.observability.api.event.DiagnosticContext;
import io.riverdb.observability.api.event.DiagnosticContextField;
import io.riverdb.observability.api.event.DiagnosticEvent;

/** Allocation-free, fail-closed field access for diagnostic exporters. */
public final class DiagnosticRedactor {
  public static final long REDACTED_VALUE = 0;

  private DiagnosticRedactor() {
  }

  public static boolean mayExportContext(
      DiagnosticContext context,
      DiagnosticContextField field,
      SensitiveFieldPolicy policy) {
    return context.has(field) && policy.permits(field.sensitivity());
  }

  public static long contextValue(
      DiagnosticContext context,
      DiagnosticContextField field,
      SensitiveFieldPolicy policy) {
    if (!mayExportContext(context, field, policy)) {
      return REDACTED_VALUE;
    }
    return context.value(field);
  }

  public static boolean mayExportEventField(
      DiagnosticEvent event,
      int fieldIndex,
      SensitiveFieldPolicy policy) {
    if (fieldIndex < 0 || fieldIndex > 3) {
      return false;
    }
    return policy.permits(event.type().fieldSensitivity(fieldIndex));
  }

  public static long eventField(
      DiagnosticEvent event,
      int fieldIndex,
      SensitiveFieldPolicy policy) {
    if (!mayExportEventField(event, fieldIndex, policy)) {
      return REDACTED_VALUE;
    }
    return event.field(fieldIndex);
  }
}
