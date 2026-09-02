package io.riverdb.base.type;

/** Small state-transfer operations for unsigned 256-bit decimal intermediates. */
final class ExactDecimal128Wide {
  private ExactDecimal128Wide() { }

  static void set128(long high, long low, ExactDecimal128.Scratch scratch) {
    scratch.w3 = 0;
    scratch.w2 = 0;
    scratch.w1 = high;
    scratch.w0 = low;
  }

  static void setDivisor128(
      long high, long low, ExactDecimal128.Scratch scratch) {
    scratch.d3 = 0;
    scratch.d2 = 0;
    scratch.d1 = high;
    scratch.d0 = low;
  }

  static void setDivisorOne(ExactDecimal128.Scratch scratch) {
    setDivisor128(0, 1, scratch);
  }

  static void wideToQuotient(ExactDecimal128.Scratch scratch) {
    scratch.q3 = scratch.w3;
    scratch.q2 = scratch.w2;
    scratch.q1 = scratch.w1;
    scratch.q0 = scratch.w0;
  }

  static void remainderToWide(ExactDecimal128.Scratch scratch) {
    scratch.w3 = scratch.r3;
    scratch.w2 = scratch.r2;
    scratch.w1 = scratch.r1;
    scratch.w0 = scratch.r0;
  }

  static boolean quotientFits128(ExactDecimal128.Scratch scratch) {
    return scratch.q3 == 0 && scratch.q2 == 0;
  }
}
