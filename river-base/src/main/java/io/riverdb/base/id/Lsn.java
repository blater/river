package io.riverdb.base.id;

/** A node-local physical WAL position. It must not be used as replica identity. */
public record Lsn(long value) implements Comparable<Lsn> {
  public static final Lsn NONE = new Lsn(-1);
  public static final Lsn BEFORE_FIRST = new Lsn(0);

  public Lsn {
    if (value < -1) {
      throw new IllegalArgumentException("LSN must be NONE or non-negative");
    }
  }

  public static Lsn of(long value) {
    return new Lsn(IdBounds.nonNegative("LSN", value));
  }

  public boolean isValid() {
    return value >= 0;
  }

  @Override
  public int compareTo(Lsn other) {
    return Long.compare(value, other.value);
  }
}
