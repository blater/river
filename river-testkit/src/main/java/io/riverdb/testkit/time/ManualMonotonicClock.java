package io.riverdb.testkit.time;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.time.MonotonicClock;

/** Single-threaded controllable clock for deterministic tests and simulations. */
public final class ManualMonotonicClock implements MonotonicClock {
  private long nowNanos;

  public ManualMonotonicClock(long initialNanos) {
    nowNanos = initialNanos;
  }

  @Override
  public long nanoTime() {
    return nowNanos;
  }

  public StatusCode advanceBy(long deltaNanos) {
    if (deltaNanos < 0 || Long.MAX_VALUE - nowNanos < deltaNanos) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    nowNanos += deltaNanos;
    return StatusCode.OK;
  }

  public StatusCode advanceTo(long targetNanos) {
    if (targetNanos < nowNanos) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    nowNanos = targetNanos;
    return StatusCode.OK;
  }
}
