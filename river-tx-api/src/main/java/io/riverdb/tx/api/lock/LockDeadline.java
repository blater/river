package io.riverdb.tx.api.lock;

/** Wrap-safe monotonic lock-wait deadline arithmetic. */
public final class LockDeadline {
  public static final long DEFAULT_WAIT_NANOS = 5_000_000_000L;

  private LockDeadline() {}

  /** Adds one validated positive duration using intentional two's-complement wrapping. */
  public static long after(long nowNanos, long durationNanos) {
    return nowNanos + durationNanos;
  }

  public static boolean expired(long deadlineNanos, long nowNanos) {
    return deadlineNanos - nowNanos <= 0;
  }

  public static long remaining(long deadlineNanos, long nowNanos) {
    long remaining = deadlineNanos - nowNanos;
    return remaining <= 0 ? 0 : remaining;
  }
}
