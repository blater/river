package io.riverdb.base.type;

/** Unsigned 128-by-128 multiplication into the caller's 256-bit workspace. */
final class ExactDecimal128WideProduct {
  private ExactDecimal128WideProduct() { }

  static void multiply(
      long leftHigh,
      long leftLow,
      long rightHigh,
      long rightLow,
      ExactDecimal128.Scratch scratch) {
    long p0 = leftLow * rightLow;
    long p1 = high(leftLow, rightLow);
    long p2 = 0;
    long p3 = 0;
    long next = p1 + leftHigh * rightLow;
    long carry = Long.compareUnsigned(next, p1) < 0 ? 1 : 0;
    p1 = next;
    next = p2 + high(leftHigh, rightLow);
    if (Long.compareUnsigned(next, p2) < 0) p3++;
    p2 = next;
    next = p2 + carry;
    if (Long.compareUnsigned(next, p2) < 0) p3++;
    p2 = next;
    next = p1 + leftLow * rightHigh;
    carry = Long.compareUnsigned(next, p1) < 0 ? 1 : 0;
    p1 = next;
    next = p2 + high(leftLow, rightHigh);
    if (Long.compareUnsigned(next, p2) < 0) p3++;
    p2 = next;
    next = p2 + carry;
    if (Long.compareUnsigned(next, p2) < 0) p3++;
    p2 = next;
    next = p2 + leftHigh * rightHigh;
    carry = Long.compareUnsigned(next, p2) < 0 ? 1 : 0;
    scratch.w3 = p3 + high(leftHigh, rightHigh) + carry;
    scratch.w2 = next;
    scratch.w1 = p1;
    scratch.w0 = p0;
  }

  private static long high(long left, long right) {
    return Math.multiplyHigh(left, right)
        + (left < 0 ? right : 0)
        + (right < 0 ? left : 0);
  }
}
