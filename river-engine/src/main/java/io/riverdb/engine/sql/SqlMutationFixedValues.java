package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.ExactDecimal;
import io.riverdb.base.type.ExactDecimal128;
import io.riverdb.base.type.ExactDecimal128Conversion;
import io.riverdb.base.type.SqlApproximateNumeric;
import io.riverdb.base.type.SqlDefaultKind;
import io.riverdb.base.type.SqlNumericTypeRules;
import io.riverdb.base.type.SqlNumericValue;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.relational.TableDefinition;

/** Resolves defaults and exact fixed-width assignment coercions. */
final class SqlMutationFixedValues {
  private final ExactDecimal.LongValue decimal = new ExactDecimal.LongValue();
  private final ExactDecimal.WideScratch wide = new ExactDecimal.WideScratch();
  private final ExactDecimal128.Value decimal128 = new ExactDecimal128.Value();
  private final ExactDecimal128.Scratch decimal128Scratch = new ExactDecimal128.Scratch();
  private final SqlTemporalContext temporal;
  private final SqlTemporalContext.LongResult result =
      new SqlTemporalContext.LongResult();

  SqlMutationFixedValues(SqlTemporalContext context) {
    temporal = context;
  }

  long value() {
    return result.value;
  }

  long highValue() { return decimal128.high; }

  void set(long value) {
    result.value = value;
    decimal128.high = value >> 63;
    decimal128.low = value;
  }

  void set(long high, long low) {
    decimal128.high = high;
    decimal128.low = low;
    result.value = low;
  }

  StatusCode defaultValue(TableDefinition table, int column) {
    int kind = table.defaultKind(column);
    if (SqlDefaultKind.isCurrent(kind)) {
      return temporal.defaultValue(kind, table.typeDescriptor(column), result);
    }
    result.value = table.defaultValue(column);
    decimal128.high = result.value >> 63;
    decimal128.low = result.value;
    return StatusCode.OK;
  }

  StatusCode coerce(long value, int source, int target) {
    return coerce(value >> 63, value, source, target);
  }

  StatusCode coerce(long high, long value, int source, int target) {
    if (SqlTypeDescriptor.isWideDecimal(source)
        || SqlTypeDescriptor.isWideDecimal(target)) {
      return coerceWide(high, value, source, target);
    }
    if (SqlNumericTypeRules.isApproximate(source)
        || SqlNumericTypeRules.isApproximate(target)) {
      StatusCode status = SqlNumericValue.assign(
          value, source, target, decimal, wide);
      if (status.isOk()) set(decimal.value);
      return status;
    }
    if (sameLocalTemporalType(source, target)) {
      set(value);
      return StatusCode.OK;
    }
    StatusCode status = ExactDecimal.quantize(
        value, source, target, false, true, decimal, wide);
    if (status.isOk()) set(decimal.value);
    return status;
  }

  StatusCode widenDecimal(long value, int source, int target) {
    if (SqlTypeDescriptor.isWideDecimal(target)) {
      return coerce(value >> 63, value, source, target);
    }
    if (!ExactDecimal.widenScale(value, source, target, decimal)) {
      return StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
    }
    set(decimal.value);
    return StatusCode.OK;
  }

  private StatusCode coerceWide(
      long high, long low, int source, int target) {
    if (SqlNumericTypeRules.isApproximate(source)
        && SqlTypeDescriptor.isWideDecimal(target)) {
      StatusCode status = ExactDecimal128Conversion.fromDouble(
          SqlNumericValue.doubleValue(low, source),
          SqlTypeDescriptor.parameterOne(target),
          SqlTypeDescriptor.parameterTwo(target), decimal128, decimal128Scratch);
      if (status.isOk()) result.value = decimal128.low;
      return status;
    }
    if (SqlTypeDescriptor.isWideDecimal(source)
        && SqlNumericTypeRules.isApproximate(target)) {
      double converted = ExactDecimal128Conversion.toDouble(
          high, low, SqlTypeDescriptor.parameterTwo(source), decimal128Scratch);
      long bits = SqlTypeDescriptor.typeId(target) == SqlTypeDescriptor.TYPE_ID_REAL
          ? SqlApproximateNumeric.realBits((float) converted)
          : SqlApproximateNumeric.doubleBits(converted);
      set(bits);
      return StatusCode.OK;
    }
    int sourceType = SqlTypeDescriptor.typeId(source);
    if (sourceType != SqlTypeDescriptor.TYPE_ID_DECIMAL
        && !SqlNumericTypeRules.isIntegral(source)) return StatusCode.DATATYPE_MISMATCH;
    int targetPrecision = precision(target);
    int targetScale = SqlTypeDescriptor.typeId(target) == SqlTypeDescriptor.TYPE_ID_DECIMAL
        ? SqlTypeDescriptor.parameterTwo(target) : 0;
    StatusCode status = sourceType == SqlTypeDescriptor.TYPE_ID_DECIMAL
        ? ExactDecimal128.quantize(
            high, low,
            SqlTypeDescriptor.parameterOne(source),
            SqlTypeDescriptor.parameterTwo(source),
            targetPrecision, targetScale,
            ExactDecimal128.ROUND_HALF_AWAY,
            SqlNumericTypeRules.isIntegral(target), decimal128, decimal128Scratch)
        : ExactDecimal128.fromLong(
            low, targetPrecision, targetScale, decimal128, decimal128Scratch);
    if (status.isOk()) result.value = decimal128.low;
    return status;
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

  private static boolean sameLocalTemporalType(int source, int target) {
    int sourceType = SqlTypeDescriptor.typeId(source);
    return sourceType == SqlTypeDescriptor.typeId(target)
        && (sourceType == SqlTypeDescriptor.TYPE_ID_TIME
            || sourceType == SqlTypeDescriptor.TYPE_ID_TIMESTAMP
            || sourceType == SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE);
  }
}
