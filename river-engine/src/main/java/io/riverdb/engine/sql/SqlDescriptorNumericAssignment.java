package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.ExactDecimal;
import io.riverdb.base.type.ExactDecimal128;
import io.riverdb.base.type.ExactDecimal128Conversion;
import io.riverdb.base.type.SqlApproximateNumeric;
import io.riverdb.base.type.SqlNumericTypeRules;
import io.riverdb.base.type.SqlNumericValue;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlValueBuffer;

/** Reusable checked coercion from one numeric descriptor value into a row buffer. */
final class SqlDescriptorNumericAssignment {
  private final ExactDecimal.LongValue decimal = new ExactDecimal.LongValue();
  private final ExactDecimal.WideScratch decimalScratch = new ExactDecimal.WideScratch();
  private final ExactDecimal128.Value decimal128 = new ExactDecimal128.Value();
  private final ExactDecimal128.Scratch decimal128Scratch = new ExactDecimal128.Scratch();

  StatusCode assign(
      SqlValueBuffer output, int column, long high, long low, int source, int target) {
    if (!SqlTypeDescriptor.isWideDecimal(source)
        && !SqlTypeDescriptor.isWideDecimal(target)) {
      StatusCode status = SqlNumericValue.assign(
          low, source, target, decimal, decimalScratch);
      return status.isOk() ? output.setFixed(column, target, decimal.value) : status;
    }
    if (SqlNumericTypeRules.isApproximate(source)
        && SqlTypeDescriptor.isWideDecimal(target)) {
      return approximateToWide(output, column, low, source, target);
    }
    if (SqlTypeDescriptor.isWideDecimal(source)
        && SqlNumericTypeRules.isApproximate(target)) {
      double converted = SqlNumericComparison.doubleValue(
          high, low, source, decimal128Scratch);
      long bits = SqlTypeDescriptor.typeId(target) == SqlTypeDescriptor.TYPE_ID_REAL
          ? SqlApproximateNumeric.realBits((float) converted)
          : SqlApproximateNumeric.doubleBits(converted);
      return output.setFixed(column, target, bits);
    }
    return exactWide(output, column, high, low, source, target);
  }

  private StatusCode approximateToWide(
      SqlValueBuffer output, int column, long low, int source, int target) {
    StatusCode status = ExactDecimal128Conversion.fromDouble(
        SqlNumericValue.doubleValue(low, source),
        SqlTypeDescriptor.parameterOne(target),
        SqlTypeDescriptor.parameterTwo(target), decimal128, decimal128Scratch);
    return status.isOk()
        ? output.setDecimal128(column, target, decimal128.high, decimal128.low)
        : status;
  }

  private StatusCode exactWide(
      SqlValueBuffer output, int column, long high, long low, int source, int target) {
    int sourceType = SqlTypeDescriptor.typeId(source);
    if (sourceType != SqlTypeDescriptor.TYPE_ID_DECIMAL
        && !SqlNumericTypeRules.isIntegral(source)) return StatusCode.DATATYPE_MISMATCH;
    int targetPrecision = precision(target);
    int targetScale = SqlTypeDescriptor.typeId(target)
        == SqlTypeDescriptor.TYPE_ID_DECIMAL
            ? SqlTypeDescriptor.parameterTwo(target) : 0;
    StatusCode status = sourceType == SqlTypeDescriptor.TYPE_ID_DECIMAL
        ? ExactDecimal128.quantize(
            high, low,
            SqlTypeDescriptor.parameterOne(source),
            SqlTypeDescriptor.parameterTwo(source),
            targetPrecision, targetScale,
            ExactDecimal128.ROUND_HALF_AWAY,
            SqlNumericTypeRules.isIntegral(target),
            decimal128, decimal128Scratch)
        : ExactDecimal128.fromLong(
            low, targetPrecision, targetScale, decimal128, decimal128Scratch);
    if (!status.isOk()) return status;
    return SqlTypeDescriptor.isWideDecimal(target)
        ? output.setDecimal128(column, target, decimal128.high, decimal128.low)
        : output.setFixed(column, target, decimal128.low);
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
