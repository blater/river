package io.riverdb.observability.api.event;

/**
 * Caller-owned fixed-field event. Producers reuse an instance; bounded sinks copy it into
 * preallocated storage before returning.
 */
public final class DiagnosticEvent {
  private final DiagnosticContext context = new DiagnosticContext();
  private EventTypeId type = EventTypeId.UNKNOWN;
  private Severity severity = Severity.INFO;
  private long monotonicNanos;
  private long sequence;
  private long field0;
  private long field1;
  private long field2;
  private long field3;

  public DiagnosticEvent reset() {
    type = EventTypeId.UNKNOWN;
    severity = Severity.INFO;
    monotonicNanos = 0;
    sequence = 0;
    field0 = 0;
    field1 = 0;
    field2 = 0;
    field3 = 0;
    context.reset();
    return this;
  }

  public DiagnosticEvent set(
      EventTypeId newType,
      Severity newSeverity,
      long newMonotonicNanos,
      long newSequence,
      DiagnosticContext newContext,
      long newField0,
      long newField1,
      long newField2,
      long newField3) {
    type = newType;
    severity = newSeverity;
    monotonicNanos = newMonotonicNanos;
    sequence = newSequence;
    context.copyFrom(newContext);
    field0 = newField0;
    field1 = newField1;
    field2 = newField2;
    field3 = newField3;
    return this;
  }

  public DiagnosticEvent copyFrom(DiagnosticEvent source) {
    type = source.type;
    severity = source.severity;
    monotonicNanos = source.monotonicNanos;
    sequence = source.sequence;
    context.copyFrom(source.context);
    field0 = source.field0;
    field1 = source.field1;
    field2 = source.field2;
    field3 = source.field3;
    return this;
  }

  public EventTypeId type() {
    return type;
  }

  public Severity severity() {
    return severity;
  }

  public long monotonicNanos() {
    return monotonicNanos;
  }

  public long sequence() {
    return sequence;
  }

  public DiagnosticContext context() {
    return context;
  }

  public long field0() {
    return field0;
  }

  public long field1() {
    return field1;
  }

  public long field2() {
    return field2;
  }

  public long field3() {
    return field3;
  }

  public long field(int index) {
    return switch (index) {
      case 0 -> field0;
      case 1 -> field1;
      case 2 -> field2;
      case 3 -> field3;
      default -> 0;
    };
  }
}
