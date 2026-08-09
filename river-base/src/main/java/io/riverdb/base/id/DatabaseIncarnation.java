package io.riverdb.base.id;

/** A durable 128-bit history identity changed by restore or reseed. */
public record DatabaseIncarnation(long high, long low) {
  public static final DatabaseIncarnation NONE = new DatabaseIncarnation(0, 0);

  public static DatabaseIncarnation of(long high, long low) {
    if (high == 0 && low == 0) {
      throw new IllegalArgumentException("database incarnation must not be zero");
    }
    return new DatabaseIncarnation(high, low);
  }

  public boolean isValid() {
    return high != 0 || low != 0;
  }
}
