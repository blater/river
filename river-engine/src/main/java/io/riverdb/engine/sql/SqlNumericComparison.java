package io.riverdb.engine.sql;

import io.riverdb.base.type.ExactDecimal128;
import io.riverdb.base.type.ExactDecimal128Conversion;
import io.riverdb.base.type.SqlNumericTypeRules;
import io.riverdb.base.type.SqlNumericValue;
import io.riverdb.base.type.SqlTypeDescriptor;

/** Compares compact and two-lane numeric values without allocating. */
final class SqlNumericComparison {
  private SqlNumericComparison() { }

  static int compare(
      long leftHigh,
      long left,
      int leftDescriptor,
      long rightHigh,
      long right,
      int rightDescriptor,
      ExactDecimal128.Scratch scratch) {
    boolean leftWide = SqlTypeDescriptor.isWideDecimal(leftDescriptor);
    boolean rightWide = SqlTypeDescriptor.isWideDecimal(rightDescriptor);
    if (!leftWide && !rightWide) {
      return SqlNumericValue.compare(left, leftDescriptor, right, rightDescriptor);
    }
    if (SqlNumericTypeRules.isExact(leftDescriptor)
        && SqlNumericTypeRules.isExact(rightDescriptor)) {
      return ExactDecimal128.compare(
          leftWide ? leftHigh : left >> 63,
          left,
          scale(leftDescriptor),
          rightWide ? rightHigh : right >> 63,
          right,
          scale(rightDescriptor),
          scratch);
    }
    return Double.compare(
        doubleValue(leftHigh, left, leftDescriptor, scratch),
        doubleValue(rightHigh, right, rightDescriptor, scratch));
  }

  static double doubleValue(
      long high, long low, int descriptor, ExactDecimal128.Scratch scratch) {
    if (!SqlTypeDescriptor.isWideDecimal(descriptor)) {
      return SqlNumericValue.doubleValue(low, descriptor);
    }
    return ExactDecimal128Conversion.toDouble(
        high, low, scale(descriptor), scratch);
  }

  private static int scale(int descriptor) {
    return SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_DECIMAL
        ? SqlTypeDescriptor.parameterTwo(descriptor) : 0;
  }
}
