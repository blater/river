package io.riverdb.base.id;

/** A compact tablespace identifier. Zero is reserved for {@link #NONE}. */
public record TablespaceId(int value) implements Comparable<TablespaceId> {
  public static final TablespaceId NONE = new TablespaceId(0);

  public TablespaceId {
    IdBounds.nonNegative("tablespace id", value);
  }

  public static TablespaceId of(int value) {
    return new TablespaceId(IdBounds.positive("tablespace id", value));
  }

  public boolean isValid() {
    return value != 0;
  }

  @Override
  public int compareTo(TablespaceId other) {
    return Integer.compare(value, other.value);
  }
}
