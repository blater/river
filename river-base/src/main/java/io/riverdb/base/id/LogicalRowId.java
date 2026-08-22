package io.riverdb.base.id;

/** Stable positive logical row identity, independent of its current MVCC version. */
public record LogicalRowId(long value) implements Comparable<LogicalRowId> {
  public static final LogicalRowId NONE = new LogicalRowId(0);

  public LogicalRowId {
    IdBounds.nonNegative("logical row id", value);
  }

  public static LogicalRowId of(long value) {
    return new LogicalRowId(IdBounds.positive("logical row id", value));
  }

  public boolean isValid() { return value != 0; }

  @Override
  public int compareTo(LogicalRowId other) { return Long.compare(value, other.value); }
}
