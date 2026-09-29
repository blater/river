package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.ExactDecimal;
import io.riverdb.base.type.ExactDecimal128;
import io.riverdb.base.type.ExactDecimal128Conversion;
import io.riverdb.base.type.SqlApproximateNumeric;
import io.riverdb.base.type.SqlNumericTypeRules;
import io.riverdb.base.type.SqlNumericValue;
import io.riverdb.base.type.SqlTypeDescriptor;

/** Reusable checked numeric coercion that publishes primitive result lanes. */
final class SqlDescriptorNumericAssignment {
  private final ExactDecimal.LongValue decimal = new ExactDecimal.LongValue();
  private final ExactDecimal.WideScratch decimalScratch = new ExactDecimal.WideScratch();
  private final ExactDecimal128.Value decimal128 = new ExactDecimal128.Value();
  private final ExactDecimal128.Scratch decimal128Scratch = new ExactDecimal128.Scratch();
  private long high;
  private long low;

  StatusCode assign(long sourceHigh, long sourceLow, int source, int target) {
    high = 0;
    low = 0;
    if (!SqlTypeDescriptor.isWideDecimal(source)
        && !SqlTypeDescriptor.isWideDecimal(target)) {
      StatusCode status = SqlNumericValue.assign(
          sourceLow, source, target, decimal, decimalScratch);
      if (status.isOk()) publishFixed(decimal.value);
      return status;
    }
    if (SqlNumericTypeRules.isApproximate(source)
        && SqlTypeDescriptor.isWideDecimal(target)) {
      return approximateToWide(sourceLow, source, target);
    }
    if (SqlTypeDescriptor.isWideDecimal(source)
        && SqlNumericTypeRules.isApproximate(target)) {
      double converted = SqlNumericComparison.doubleValue(
          sourceHigh, sourceLow, source, decimal128Scratch);
      long bits = SqlTypeDescriptor.typeId(target) == SqlTypeDescriptor.TYPE_ID_REAL
          ? SqlApproximateNumeric.realBits((float) converted)
          : SqlApproximateNumeric.doubleBits(converted);
      publishFixed(bits);
      return StatusCode.OK;
    }
    return exactWide(sourceHigh, sourceLow, source, target);
  }

  long high() { return high; }
  long low() { return low; }

  private StatusCode approximateToWide(long value, int source, int target) {
    StatusCode status = ExactDecimal128Conversion.fromDouble(
        SqlNumericValue.doubleValue(value, source),
        SqlTypeDescriptor.parameterOne(target),
        SqlTypeDescriptor.parameterTwo(target), decimal128, decimal128Scratch);
    if (status.isOk()) publishWide(decimal128.high, decimal128.low);
    return status;
  }

  private StatusCode exactWide(
      long sourceHigh, long sourceLow, int source, int target) {
    int sourceType = SqlTypeDescriptor.typeId(source);
    if (sourceType != SqlTypeDescriptor.TYPE_ID_DECIMAL
        && !SqlNumericTypeRules.isIntegral(source)) return StatusCode.DATATYPE_MISMATCH;
    int targetPrecision = precision(target);
    int targetScale = SqlTypeDescriptor.typeId(target)
        == SqlTypeDescriptor.TYPE_ID_DECIMAL
            ? SqlTypeDescriptor.parameterTwo(target) : 0;
    StatusCode status = sourceType == SqlTypeDescriptor.TYPE_ID_DECIMAL
        ? ExactDecimal128.quantize(
            sourceHigh, sourceLow,
            SqlTypeDescriptor.parameterOne(source),
            SqlTypeDescriptor.parameterTwo(source),
            targetPrecision, targetScale,
            ExactDecimal128.ROUND_HALF_AWAY,
            SqlNumericTypeRules.isIntegral(target),
            decimal128, decimal128Scratch)
        : ExactDecimal128.fromLong(
            sourceLow, targetPrecision, targetScale, decimal128, decimal128Scratch);
    if (!status.isOk()) return status;
    if (SqlTypeDescriptor.isWideDecimal(target)) {
      publishWide(decimal128.high, decimal128.low);
    } else publishFixed(decimal128.low);
    return StatusCode.OK;
  }

  private void publishFixed(long value) {
    high = value >> 63;
    low = value;
  }

  private void publishWide(long valueHigh, long valueLow) {
    high = valueHigh;
    low = valueLow;
  }

  private static int precision(int descriptor) {
    return switch (SqlTypeDescriptor.typeId(descriptor)) {
      case SqlTypeDescriptor.TYPE_ID_SMALLINT -> 5;
      case SqlTypeDescriptor.TYPE_ID_INTEGER -> 10;
      case SqlTypeDescriptor.TYPE_ID_BIGINT -> 19;
      case SqlTypeDescriptor.TYPE_ID_DECIMAL -> SqlTypeDescriptor.parameterOne(descriptor);
      default -> 0;
    };
  }
}
