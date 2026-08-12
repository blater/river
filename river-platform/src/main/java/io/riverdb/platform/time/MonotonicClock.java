package io.riverdb.platform.time;

/** A duration clock that is not affected by wall-clock corrections. */
@FunctionalInterface
public interface MonotonicClock {
  long nanoTime();
}
