package io.riverdb.base.type;

/** Shared allocation-free 256-bit shifts and rounding state for decimal conversion. */
final class ExactDecimal128ConversionWide {
  private ExactDecimal128ConversionWide() { }

  static int compareTwiceRemainder(ExactDecimal128.Scratch scratch) {
    long high3 = scratch.r3 << 1 | scratch.r2 >>> 63;
    long high2 = scratch.r2 << 1 | scratch.r1 >>> 63;
    long high1 = scratch.r1 << 1 | scratch.r0 >>> 63;
    long high0 = scratch.r0 << 1;
    int compared = Long.compareUnsigned(high3, scratch.d3);
    if (compared == 0) compared = Long.compareUnsigned(high2, scratch.d2);
    if (compared == 0) compared = Long.compareUnsigned(high1, scratch.d1);
    return compared == 0 ? Long.compareUnsigned(high0, scratch.d0) : compared;
  }

  static void incrementQuotient(ExactDecimal128.Scratch scratch) {
    if (++scratch.q0 != 0) return;
    if (++scratch.q1 != 0) return;
    if (++scratch.q2 != 0) return;
    scratch.q3++;
  }

  static boolean shiftValue(int shift, ExactDecimal128.Scratch scratch) {
    int words = shift >>> 6;
    for (int index = 0; index < words; index++) {
      if (scratch.w3 != 0) return false;
      scratch.w3 = scratch.w2;
      scratch.w2 = scratch.w1;
      scratch.w1 = scratch.w0;
      scratch.w0 = 0;
    }
    int bits = shift & 63;
    if (bits == 0) return true;
    if (scratch.w3 >>> 64 - bits != 0) return false;
    scratch.w3 = scratch.w3 << bits | scratch.w2 >>> 64 - bits;
    scratch.w2 = scratch.w2 << bits | scratch.w1 >>> 64 - bits;
    scratch.w1 = scratch.w1 << bits | scratch.w0 >>> 64 - bits;
    scratch.w0 <<= bits;
    return true;
  }

  static void shiftDivisor(int shift, ExactDecimal128.Scratch scratch) {
    int words = shift >>> 6;
    for (int index = 0; index < words; index++) {
      scratch.d3 = scratch.d2;
      scratch.d2 = scratch.d1;
      scratch.d1 = scratch.d0;
      scratch.d0 = 0;
    }
    int bits = shift & 63;
    if (bits == 0) return;
    scratch.d3 = scratch.d3 << bits | scratch.d2 >>> 64 - bits;
    scratch.d2 = scratch.d2 << bits | scratch.d1 >>> 64 - bits;
    scratch.d1 = scratch.d1 << bits | scratch.d0 >>> 64 - bits;
    scratch.d0 <<= bits;
  }
}
