package io.riverdb.base.id;

/** A stable 128-bit client request identity. */
public record RequestId(long high, long low) {
  public static final RequestId NONE = new RequestId(0, 0);

  public static RequestId of(long high, long low) {
    if (high == 0 && low == 0) {
      throw new IllegalArgumentException("request id must not be zero");
    }
    return new RequestId(high, low);
  }

  public boolean isValid() {
    return high != 0 || low != 0;
  }
}
