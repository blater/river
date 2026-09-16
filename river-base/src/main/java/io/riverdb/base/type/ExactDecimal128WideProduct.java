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
    long p1 = Math.unsignedMultiplyHigh(leftLow, rightLow);
    long p2 = 0;
    long p3 = 0;
    long next = p1 + leftHigh * rightLow;
    long carry = carry(next, p1);
    p1 = next;
    next = p2 + Math.unsignedMultiplyHigh(leftHigh, rightLow);
    p3 += carry(next, p2);
    p2 = next;
    next = p2 + carry;
    p3 += carry(next, p2);
    p2 = next;
    next = p1 + leftLow * rightHigh;
    carry = carry(next, p1);
    p1 = next;
    next = p2 + Math.unsignedMultiplyHigh(leftLow, rightHigh);
    p3 += carry(next, p2);
    p2 = next;
    next = p2 + carry;
    p3 += carry(next, p2);
    p2 = next;
    next = p2 + leftHigh * rightHigh;
    carry = carry(next, p2);
    scratch.w3 = p3 + Math.unsignedMultiplyHigh(leftHigh, rightHigh) + carry;
    scratch.w2 = next;
    scratch.w1 = p1;
    scratch.w0 = p0;
  }

  private static long carry(long sum, long addend) {
    return Long.compareUnsigned(sum, addend) < 0 ? 1 : 0;
  }
}
