package io.riverdb.jdbc;

import java.math.BigDecimal;
import java.math.BigInteger;

/** JDBC-boundary conversion between signed primitive pairs and BigDecimal. */
final class RiverJdbcDecimal128 {
  private RiverJdbcDecimal128() { }

  static long high(BigDecimal value) {
    return value.unscaledValue().shiftRight(Long.SIZE).longValue();
  }

  static long low(BigDecimal value) {
    return value.unscaledValue().longValue();
  }

  static BigDecimal value(long high, long low, int scale) {
    if (high == low >> (Long.SIZE - 1)) return BigDecimal.valueOf(low, scale);
    byte[] signed = new byte[Long.BYTES * 2];
    putLong(signed, 0, high);
    putLong(signed, Long.BYTES, low);
    return new BigDecimal(new BigInteger(signed), scale);
  }

  private static void putLong(byte[] target, int offset, long value) {
    for (int index = 0; index < Long.BYTES; index++) {
      target[offset + index] = (byte) (
          value >>> (Long.BYTES - index - 1) * Byte.SIZE);
    }
  }
}
