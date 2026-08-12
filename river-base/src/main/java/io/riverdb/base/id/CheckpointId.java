package io.riverdb.base.id;

/** The identity of a completed recovery-checkpoint boundary. */
public record CheckpointId(long value) implements Comparable<CheckpointId> {
  public static final CheckpointId NONE = new CheckpointId(0);

  public CheckpointId {
    IdBounds.nonNegative("checkpoint id", value);
  }

  public static CheckpointId of(long value) {
    return new CheckpointId(IdBounds.positive("checkpoint id", value));
  }

  public boolean isValid() {
    return value != 0;
  }

  @Override
  public int compareTo(CheckpointId other) {
    return Long.compare(value, other.value);
  }
}
