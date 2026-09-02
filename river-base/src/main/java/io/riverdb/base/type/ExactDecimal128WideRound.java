package io.riverdb.base.type;

/** Half-even rounding of a 256-bit quotient and remainder. */
final class ExactDecimal128WideRound {
  private ExactDecimal128WideRound() { }

  static boolean halfEven(ExactDecimal128.Scratch scratch) {
    if ((scratch.r3 | scratch.r2 | scratch.r1 | scratch.r0) == 0) return true;
    int comparison = compareDoubledRemainder(scratch);
    if (comparison < 0 || comparison == 0 && (scratch.q0 & 1) == 0) return true;
    return increment(scratch);
  }

  private static int compareDoubledRemainder(
      ExactDecimal128.Scratch scratch) {
    long v3 = scratch.r3 << 1 | scratch.r2 >>> 63;
    long v2 = scratch.r2 << 1 | scratch.r1 >>> 63;
    long v1 = scratch.r1 << 1 | scratch.r0 >>> 63;
    long v0 = scratch.r0 << 1;
    int comparison = Long.compareUnsigned(v3, scratch.d3);
    if (comparison != 0) return comparison;
    comparison = Long.compareUnsigned(v2, scratch.d2);
    if (comparison != 0) return comparison;
    comparison = Long.compareUnsigned(v1, scratch.d1);
    return comparison != 0 ? comparison : Long.compareUnsigned(v0, scratch.d0);
  }

  private static boolean increment(ExactDecimal128.Scratch scratch) {
    long q0 = scratch.q0 + 1;
    long carry = q0 == 0 ? 1 : 0;
    long q1 = scratch.q1 + carry;
    carry = carry != 0 && q1 == 0 ? 1 : 0;
    long q2 = scratch.q2 + carry;
    carry = carry != 0 && q2 == 0 ? 1 : 0;
    long q3 = scratch.q3 + carry;
    if (carry != 0 && q3 == 0) return false;
    scratch.q0 = q0;
    scratch.q1 = q1;
    scratch.q2 = q2;
    scratch.q3 = q3;
    return true;
  }
}
