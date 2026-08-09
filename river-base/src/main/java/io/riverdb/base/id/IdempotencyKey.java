package io.riverdb.base.id;

/** A stable 128-bit operation identity used to resolve retries. */
public record IdempotencyKey(long high, long low) {
  public static final IdempotencyKey NONE = new IdempotencyKey(0, 0);

  public static IdempotencyKey of(long high, long low) {
    if (high == 0 && low == 0) {
      throw new IllegalArgumentException("idempotency key must not be zero");
    }
    return new IdempotencyKey(high, low);
  }

  public boolean isValid() {
    return high != 0 || low != 0;
  }
}
