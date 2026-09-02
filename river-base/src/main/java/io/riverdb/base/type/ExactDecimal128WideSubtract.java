package io.riverdb.base.type;

/** Borrow propagation for a 256-bit division remainder. */
final class ExactDecimal128WideSubtract {
  private ExactDecimal128WideSubtract() { }

  static void divisor(ExactDecimal128.Scratch scratch) {
    long r0 = scratch.r0 - scratch.d0;
    long borrow0 = Long.compareUnsigned(scratch.r0, scratch.d0) < 0 ? 1 : 0;
    long d1 = scratch.d1 + borrow0;
    long borrow1 = Long.compareUnsigned(d1, scratch.d1) < 0
        || Long.compareUnsigned(scratch.r1, d1) < 0 ? 1 : 0;
    long r1 = scratch.r1 - d1;
    long d2 = scratch.d2 + borrow1;
    long borrow2 = Long.compareUnsigned(d2, scratch.d2) < 0
        || Long.compareUnsigned(scratch.r2, d2) < 0 ? 1 : 0;
    scratch.r0 = r0;
    scratch.r1 = r1;
    scratch.r2 -= d2;
    scratch.r3 -= scratch.d3 + borrow2;
  }
}
