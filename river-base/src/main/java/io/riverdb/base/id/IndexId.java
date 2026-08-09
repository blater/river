package io.riverdb.base.id;

/** A compact catalog index identifier. Zero is reserved for {@link #NONE}. */
public record IndexId(int value) implements Comparable<IndexId> {
  public static final IndexId NONE = new IndexId(0);

  public IndexId {
    IdBounds.nonNegative("index id", value);
  }

  public static IndexId of(int value) {
    return new IndexId(IdBounds.positive("index id", value));
  }

  public boolean isValid() {
    return value != 0;
  }

  @Override
  public int compareTo(IndexId other) {
    return Integer.compare(value, other.value);
  }
}
