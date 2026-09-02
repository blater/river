package io.riverdb.engine.api;

/** Overflow-checked sizing shared by validation scratch structures. */
final class TransactionProgramValidationSizing {
  private TransactionProgramValidationSizing() { }

  static int levels(int steps) {
    return steps <= 0 ? 0 : 32 - Integer.numberOfLeadingZeros(steps);
  }

  static int tableCapacity(int entries) {
    if (entries <= 0) return 0;
    long required = (long) entries * 2L + 1L;
    if (required > Integer.MAX_VALUE) return -1;
    int capacity = 1;
    while (capacity < required) {
      if (capacity > Integer.MAX_VALUE / 2) return -1;
      capacity <<= 1;
    }
    return capacity;
  }

  static int multiply(int left, int right) {
    long result = (long) left * right;
    return result > Integer.MAX_VALUE ? -1 : (int) result;
  }

  static long add(long left, long right) {
    return right < 0 || left > Long.MAX_VALUE - right ? -1 : left + right;
  }
}
