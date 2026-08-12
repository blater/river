package io.riverdb.observability.api.event;

import io.riverdb.observability.api.redaction.Sensitivity;

/**
 * Stable event registry. Stable IDs and canonical names are externally visible and must never be
 * renumbered or reused.
 */
public enum EventTypeId {
  UNKNOWN(0, "river.unknown", 1,
      Sensitivity.SENSITIVE, Sensitivity.SENSITIVE,
      Sensitivity.SENSITIVE, Sensitivity.SENSITIVE),
  DATABASE_STARTED(1000, "river.database.started", 1,
      Sensitivity.INTERNAL, Sensitivity.PUBLIC,
      Sensitivity.PUBLIC, Sensitivity.PUBLIC),
  DATABASE_STOPPED(1001, "river.database.stopped", 1,
      Sensitivity.INTERNAL, Sensitivity.PUBLIC,
      Sensitivity.PUBLIC, Sensitivity.PUBLIC),
  COMPONENT_FENCED(1002, "river.component.fenced", 1,
      Sensitivity.INTERNAL, Sensitivity.INTERNAL,
      Sensitivity.INTERNAL, Sensitivity.PUBLIC),
  DIAGNOSTIC_QUEUE_SATURATED(1003, "river.diagnostic.queue_saturated", 1,
      Sensitivity.PUBLIC, Sensitivity.PUBLIC,
      Sensitivity.PUBLIC, Sensitivity.PUBLIC),
  WAL_STALL(2000, "river.wal.stall", 1,
      Sensitivity.INTERNAL, Sensitivity.PUBLIC,
      Sensitivity.PUBLIC, Sensitivity.PUBLIC),
  CHECKPOINT_COMPLETED(2001, "river.checkpoint.completed", 1,
      Sensitivity.INTERNAL, Sensitivity.INTERNAL,
      Sensitivity.PUBLIC, Sensitivity.PUBLIC);

  private static final EventTypeId[] REGISTRY = values();

  private final int stableId;
  private final String canonicalName;
  private final int schemaVersion;
  private final Sensitivity field0Sensitivity;
  private final Sensitivity field1Sensitivity;
  private final Sensitivity field2Sensitivity;
  private final Sensitivity field3Sensitivity;

  EventTypeId(
      int stableId,
      String canonicalName,
      int schemaVersion,
      Sensitivity field0Sensitivity,
      Sensitivity field1Sensitivity,
      Sensitivity field2Sensitivity,
      Sensitivity field3Sensitivity) {
    this.stableId = stableId;
    this.canonicalName = canonicalName;
    this.schemaVersion = schemaVersion;
    this.field0Sensitivity = field0Sensitivity;
    this.field1Sensitivity = field1Sensitivity;
    this.field2Sensitivity = field2Sensitivity;
    this.field3Sensitivity = field3Sensitivity;
  }

  public int stableId() {
    return stableId;
  }

  public String canonicalName() {
    return canonicalName;
  }

  public int schemaVersion() {
    return schemaVersion;
  }

  /** Invalid field indexes fail closed as sensitive rather than throwing on an export path. */
  public Sensitivity fieldSensitivity(int fieldIndex) {
    return switch (fieldIndex) {
      case 0 -> field0Sensitivity;
      case 1 -> field1Sensitivity;
      case 2 -> field2Sensitivity;
      case 3 -> field3Sensitivity;
      default -> Sensitivity.SENSITIVE;
    };
  }

  public static EventTypeId fromStableId(int stableId) {
    for (EventTypeId candidate : REGISTRY) {
      if (candidate.stableId == stableId) {
        return candidate;
      }
    }
    return UNKNOWN;
  }
}
