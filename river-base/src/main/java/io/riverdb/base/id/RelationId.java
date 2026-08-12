package io.riverdb.base.id;

/** A compact catalog relation identifier. Zero is reserved for {@link #NONE}. */
public record RelationId(int value) implements Comparable<RelationId> {
  public static final RelationId NONE = new RelationId(0);

  public RelationId {
    IdBounds.nonNegative("relation id", value);
  }

  public static RelationId of(int value) {
    return new RelationId(IdBounds.positive("relation id", value));
  }

  public boolean isValid() {
    return value != 0;
  }

  @Override
  public int compareTo(RelationId other) {
    return Integer.compare(value, other.value);
  }
}
