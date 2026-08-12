package io.riverdb.base.id;

/** A durable database-container identifier. Zero is reserved for {@link #NONE}. */
public record DatabaseId(long value) implements Comparable<DatabaseId> {
  public static final DatabaseId NONE = new DatabaseId(0);

  public DatabaseId {
    IdBounds.nonNegative("database id", value);
  }

  public static DatabaseId of(long value) {
    return new DatabaseId(IdBounds.positive("database id", value));
  }

  public boolean isValid() {
    return value != 0;
  }

  @Override
  public int compareTo(DatabaseId other) {
    return Long.compare(value, other.value);
  }
}
