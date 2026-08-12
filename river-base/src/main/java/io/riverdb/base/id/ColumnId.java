package io.riverdb.base.id;

/** A compact relation-local column identifier. Zero is reserved for {@link #NONE}. */
public record ColumnId(int value) implements Comparable<ColumnId> {
  public static final ColumnId NONE = new ColumnId(0);

  public ColumnId {
    IdBounds.nonNegative("column id", value);
  }

  public static ColumnId of(int value) {
    return new ColumnId(IdBounds.positive("column id", value));
  }

  public boolean isValid() {
    return value != 0;
  }

  @Override
  public int compareTo(ColumnId other) {
    return Integer.compare(value, other.value);
  }
}
