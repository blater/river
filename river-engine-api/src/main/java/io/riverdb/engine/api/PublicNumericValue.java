package io.riverdb.engine.api;

import io.riverdb.base.type.SqlTypeDescriptor;

/** Typed views over one validated canonical fixed-value lane. */
final class PublicNumericValue {
  private PublicNumericValue() { }

  static short smallint(int descriptor, long value) {
    return type(descriptor, SqlTypeDescriptor.TYPE_ID_SMALLINT) ? (short) value : 0;
  }

  static int integer(int descriptor, long value) {
    return type(descriptor, SqlTypeDescriptor.TYPE_ID_INTEGER) ? (int) value : 0;
  }

  static long bigint(int descriptor, long value) {
    return type(descriptor, SqlTypeDescriptor.TYPE_ID_BIGINT) ? value : 0;
  }

  static long decimal(int descriptor, long value) {
    return type(descriptor, SqlTypeDescriptor.TYPE_ID_DECIMAL)
            && !PublicDecimal128.isWide(descriptor)
        ? value : 0;
  }

  static long decimalHigh(int descriptor, long high, long low) {
    if (!type(descriptor, SqlTypeDescriptor.TYPE_ID_DECIMAL)) return 0;
    return PublicDecimal128.isWide(descriptor) ? high : low >> Long.SIZE - 1;
  }

  static long decimalLow(int descriptor, long value) {
    return type(descriptor, SqlTypeDescriptor.TYPE_ID_DECIMAL) ? value : 0;
  }

  static float real(int descriptor, long value) {
    return type(descriptor, SqlTypeDescriptor.TYPE_ID_REAL)
        ? Float.intBitsToFloat((int) value) : 0.0f;
  }

  static double doubleValue(int descriptor, long value) {
    return type(descriptor, SqlTypeDescriptor.TYPE_ID_DOUBLE)
        ? Double.longBitsToDouble(value) : 0.0d;
  }

  private static boolean type(int descriptor, int expected) {
    return SqlTypeDescriptor.typeId(descriptor) == expected;
  }
}
