package io.riverdb.bench.tpcc;

/** Saturates benchmark counters and retains overflow for artifact validation. */
abstract class TpccCounterArithmetic {
  protected boolean overflowed;

  final long increment(long value) {
    if (value == Long.MAX_VALUE) {
      overflowed = true;
      return value;
    }
    return value + 1;
  }

  final long add(long current, long value) {
    if (current < 0 || value < 0 || current > Long.MAX_VALUE - value) {
      overflowed = true;
      return Long.MAX_VALUE;
    }
    return current + value;
  }

  final long addSigned(long current, long value) {
    if (value > 0 && current > Long.MAX_VALUE - value
        || value < 0 && current < Long.MIN_VALUE - value) {
      overflowed = true;
      return value < 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
    }
    return current + value;
  }
}
