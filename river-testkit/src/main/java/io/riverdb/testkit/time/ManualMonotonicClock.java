package io.riverdb.testkit.time;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.time.MonotonicClock;

/** Event-loop-owned controllable clock for deterministic tests and simulations. */
public final class ManualMonotonicClock implements MonotonicClock {
  private final Thread eventLoopThread;
  private long nowNanos;

  public ManualMonotonicClock(long initialNanos) {
    eventLoopThread = Thread.currentThread();
    nowNanos = initialNanos;
  }

  @Override
  public synchronized long nanoTime() {
    return nowNanos;
  }

  public synchronized StatusCode advanceBy(long deltaNanos) {
    if (Thread.currentThread() != eventLoopThread) {
      return StatusCode.NOT_OWNER;
    }
    if (deltaNanos < 0 || Long.MAX_VALUE - nowNanos < deltaNanos) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    nowNanos += deltaNanos;
    return StatusCode.OK;
  }

  public synchronized StatusCode advanceTo(long targetNanos) {
    if (Thread.currentThread() != eventLoopThread) {
      return StatusCode.NOT_OWNER;
    }
    if (targetNanos < nowNanos) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    nowNanos = targetNanos;
    return StatusCode.OK;
  }
}
