package io.riverdb.tx;

import io.riverdb.base.error.StatusCode;

/** Bounded, allocation-free counters for the lock wait lifecycle. */
final class LockWaitCounters {
  private long entered;
  private long actuallyBlocked;
  private long blockedNanos;
  private long granted;
  private long timedOut;
  private long deadlock;
  private long cancelled;

  void entered() { entered = increment(entered); }

  void blocked() { actuallyBlocked = increment(actuallyBlocked); }

  void completeBlocked(long startedNanos, long completedNanos) {
    long elapsed = completedNanos - startedNanos;
    if (elapsed > 0) blockedNanos = add(blockedNanos, elapsed);
  }

  void granted() { granted = increment(granted); }

  void terminal(StatusCode status) {
    if (status == StatusCode.TIMEOUT) timedOut = increment(timedOut);
    else if (status == StatusCode.DEADLOCK) deadlock = increment(deadlock);
    else if (status == StatusCode.CANCELLED) cancelled = increment(cancelled);
  }

  long enteredCount() { return entered; }

  long actuallyBlockedCount() { return actuallyBlocked; }

  long blockedNanos() { return blockedNanos; }

  long grantedCount() { return granted; }

  long timedOutCount() { return timedOut; }

  long deadlockCount() { return deadlock; }

  long cancelledCount() { return cancelled; }

  static boolean escalationSupported() { return false; }

  static long escalationCount() { return 0; }

  private static long increment(long value) {
    return value == Long.MAX_VALUE ? Long.MAX_VALUE : value + 1;
  }

  private static long add(long value, long delta) {
    return delta > Long.MAX_VALUE - value ? Long.MAX_VALUE : value + delta;
  }

}
