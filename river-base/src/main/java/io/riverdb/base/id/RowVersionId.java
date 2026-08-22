package io.riverdb.base.id;

/** Positive MVCC version identity, distinct from stable logical row and physical heap slot. */
public record RowVersionId(long value) implements Comparable<RowVersionId> {
  public static final RowVersionId NONE = new RowVersionId(0);

  public RowVersionId {
    IdBounds.nonNegative("row version id", value);
  }

  public static RowVersionId of(long value) {
    return new RowVersionId(IdBounds.positive("row version id", value));
  }

  public boolean isValid() {
    return value != 0;
  }

  @Override
  public int compareTo(RowVersionId other) {
    return Long.compare(value, other.value);
  }
}
