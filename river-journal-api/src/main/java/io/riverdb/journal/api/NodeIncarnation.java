package io.riverdb.journal.api;

/** Process/storage-node lifetime identity changed on every provider restart. */
public record NodeIncarnation(long high, long low) {
  public static final NodeIncarnation NONE = new NodeIncarnation(0, 0);

  public static NodeIncarnation of(long high, long low) {
    if (high == 0 && low == 0) {
      throw new IllegalArgumentException("node incarnation must not be zero");
    }
    return new NodeIncarnation(high, low);
  }

  public boolean isValid() {
    return high != 0 || low != 0;
  }
}
