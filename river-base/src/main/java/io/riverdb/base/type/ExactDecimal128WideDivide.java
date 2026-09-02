package io.riverdb.base.type;

/** Restoring unsigned 256-bit division over caller-owned scratch fields. */
final class ExactDecimal128WideDivide {
  private ExactDecimal128WideDivide() { }

  static void divide(ExactDecimal128.Scratch scratch) {
    clear(scratch);
    for (int bit = 255; bit >= 0; bit--) {
      shiftRemainder(inputBit(bit, scratch), scratch);
      if (compareRemainder(scratch) >= 0) {
        ExactDecimal128WideSubtract.divisor(scratch);
        setQuotientBit(bit, scratch);
      }
    }
  }

  private static void clear(ExactDecimal128.Scratch scratch) {
    scratch.q3 = 0;
    scratch.q2 = 0;
    scratch.q1 = 0;
    scratch.q0 = 0;
    scratch.r3 = 0;
    scratch.r2 = 0;
    scratch.r1 = 0;
    scratch.r0 = 0;
  }

  private static int inputBit(int bit, ExactDecimal128.Scratch scratch) {
    if (bit >= 192) return (int) (scratch.w3 >>> bit - 192 & 1);
    if (bit >= 128) return (int) (scratch.w2 >>> bit - 128 & 1);
    if (bit >= 64) return (int) (scratch.w1 >>> bit - 64 & 1);
    return (int) (scratch.w0 >>> bit & 1);
  }

  private static void shiftRemainder(
      int inputBit, ExactDecimal128.Scratch scratch) {
    scratch.r3 = scratch.r3 << 1 | scratch.r2 >>> 63;
    scratch.r2 = scratch.r2 << 1 | scratch.r1 >>> 63;
    scratch.r1 = scratch.r1 << 1 | scratch.r0 >>> 63;
    scratch.r0 = scratch.r0 << 1 | inputBit;
  }

  private static int compareRemainder(ExactDecimal128.Scratch scratch) {
    int comparison = Long.compareUnsigned(scratch.r3, scratch.d3);
    if (comparison != 0) return comparison;
    comparison = Long.compareUnsigned(scratch.r2, scratch.d2);
    if (comparison != 0) return comparison;
    comparison = Long.compareUnsigned(scratch.r1, scratch.d1);
    return comparison != 0
        ? comparison : Long.compareUnsigned(scratch.r0, scratch.d0);
  }

  private static void setQuotientBit(
      int bit, ExactDecimal128.Scratch scratch) {
    if (bit >= 192) scratch.q3 |= 1L << bit - 192;
    else if (bit >= 128) scratch.q2 |= 1L << bit - 128;
    else if (bit >= 64) scratch.q1 |= 1L << bit - 64;
    else scratch.q0 |= 1L << bit;
  }
}
