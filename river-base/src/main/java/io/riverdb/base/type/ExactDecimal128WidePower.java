package io.riverdb.base.type;

/** Power-of-ten scaling for a selected unsigned 256-bit workspace value. */
final class ExactDecimal128WidePower {
  private static final int MAXIMUM_POWER = ExactDecimal128.MAXIMUM_PRECISION * 2;

  private ExactDecimal128WidePower() { }

  static boolean multiplyValue(int exponent, ExactDecimal128.Scratch scratch) {
    return multiply(exponent, false, scratch);
  }

  static boolean multiplyDivisor(int exponent, ExactDecimal128.Scratch scratch) {
    return multiply(exponent, true, scratch);
  }

  private static boolean multiply(
      int exponent, boolean divisor, ExactDecimal128.Scratch scratch) {
    if (exponent < 0 || exponent > MAXIMUM_POWER) return false;
    long v3 = divisor ? scratch.d3 : scratch.w3;
    long v2 = divisor ? scratch.d2 : scratch.w2;
    long v1 = divisor ? scratch.d1 : scratch.w1;
    long v0 = divisor ? scratch.d0 : scratch.w0;
    for (int index = 0; index < exponent; index++) {
      long carry0 = high(v0, 10);
      long carry1 = high(v1, 10);
      long carry2 = high(v2, 10);
      long carry3 = high(v3, 10);
      v0 *= 10;
      long next = v1 * 10 + carry0;
      if (Long.compareUnsigned(next, carry0) < 0) carry1++;
      v1 = next;
      next = v2 * 10 + carry1;
      if (Long.compareUnsigned(next, carry1) < 0) carry2++;
      v2 = next;
      next = v3 * 10 + carry2;
      if (Long.compareUnsigned(next, carry2) < 0) carry3++;
      if (carry3 != 0) return false;
      v3 = next;
    }
    publish(divisor, v3, v2, v1, v0, scratch);
    return true;
  }

  private static void publish(
      boolean divisor,
      long v3,
      long v2,
      long v1,
      long v0,
      ExactDecimal128.Scratch scratch) {
    if (divisor) {
      scratch.d3 = v3;
      scratch.d2 = v2;
      scratch.d1 = v1;
      scratch.d0 = v0;
    } else {
      scratch.w3 = v3;
      scratch.w2 = v2;
      scratch.w1 = v1;
      scratch.w0 = v0;
    }
  }

  private static long high(long left, long right) {
    return Math.multiplyHigh(left, right)
        + (left < 0 ? right : 0)
        + (right < 0 ? left : 0);
  }
}
