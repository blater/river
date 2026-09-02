package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.ExactDecimal;
import io.riverdb.base.type.ExactDecimal128;
import io.riverdb.base.type.ExactDecimal128Arithmetic;
import io.riverdb.base.type.SqlApproximateNumeric;
import io.riverdb.base.type.SqlNumericTypeRules;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlValueDomain;
import io.riverdb.sql.SqlAggregateKind;

/** Finalizes caller-owned numeric aggregate lanes without allocating. */
final class SqlAggregateNumericFinish {
  private final ExactDecimal.LongValue decimal = new ExactDecimal.LongValue();
  private final ExactDecimal.WideScratch compactScratch = new ExactDecimal.WideScratch();
  private final ExactDecimal128.Value decimal128 = new ExactDecimal128.Value();
  private final ExactDecimal128.Scratch wideScratch = new ExactDecimal128.Scratch();

  StatusCode finish(
      SqlBoundAggregateSet aggregates,
      long[] highs,
      long[] values,
      long[] counts,
      boolean[] nulls) {
    for (int slot = 0; slot < aggregates.count(); slot++) {
      if (nulls[slot]) continue;
      StatusCode status = finishOne(aggregates, highs, values, counts, slot);
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  private StatusCode finishOne(
      SqlBoundAggregateSet aggregates,
      long[] highs,
      long[] values,
      long[] counts,
      int slot) {
    int kind = aggregates.kind(slot);
    int input = aggregates.inputDescriptor(slot);
    int result = aggregates.resultDescriptor(slot);
    if (SqlNumericTypeRules.isApproximate(input)
        && (kind == SqlAggregateKind.SUM || kind == SqlAggregateKind.AVG)) {
      double value = Double.longBitsToDouble(values[slot]);
      return publishApproximate(
          values, slot, kind == SqlAggregateKind.AVG ? value / counts[slot] : value, result);
    }
    if (kind == SqlAggregateKind.SUM) return validateSum(highs, values, slot, result);
    if (kind != SqlAggregateKind.AVG) return StatusCode.OK;
    return SqlTypeDescriptor.isWideDecimal(result)
        ? averageWide(highs, values, counts, slot, input, result)
        : averageCompact(highs, values, counts, slot, input, result);
  }

  private StatusCode validateSum(
      long[] highs, long[] values, int slot, int descriptor) {
    if (!SqlTypeDescriptor.isWideDecimal(descriptor)
        && highs[slot] != values[slot] >> 63) {
      return StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
    }
    if (SqlTypeDescriptor.typeId(descriptor) != SqlTypeDescriptor.TYPE_ID_DECIMAL) {
      return StatusCode.OK;
    }
    int precision = SqlTypeDescriptor.parameterOne(descriptor);
    return (SqlTypeDescriptor.isWideDecimal(descriptor)
        ? ExactDecimal128.fits(highs[slot], values[slot], precision)
        : ExactDecimal.fits(values[slot], precision))
            ? StatusCode.OK : StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
  }

  private StatusCode averageWide(
      long[] highs,
      long[] values,
      long[] counts,
      int slot,
      int input,
      int result) {
    StatusCode status = ExactDecimal128Arithmetic.divideByLong(
        highs[slot], values[slot], SqlTypeDescriptor.MAXIMUM_DECIMAL_PRECISION,
        scale(input), counts[slot],
        SqlTypeDescriptor.parameterOne(result), SqlTypeDescriptor.parameterTwo(result),
        decimal128, wideScratch);
    if (status.isOk()) {
      highs[slot] = decimal128.high;
      values[slot] = decimal128.low;
    }
    return status;
  }

  private StatusCode averageCompact(
      long[] highs,
      long[] values,
      long[] counts,
      int slot,
      int input,
      int result) {
    if (!ExactDecimal.average(
        highs[slot], values[slot], counts[slot], scale(input), result,
        decimal, compactScratch)) return StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
    values[slot] = decimal.value;
    return StatusCode.OK;
  }

  private static StatusCode publishApproximate(
      long[] values, int slot, double value, int descriptor) {
    if (!Double.isFinite(value)) return StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
    long bits = SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_REAL
        ? SqlApproximateNumeric.realBits((float) value)
        : SqlApproximateNumeric.doubleBits(value);
    if (!SqlValueDomain.validFixed(descriptor, bits)) {
      return StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
    }
    values[slot] = bits;
    return StatusCode.OK;
  }

  private static int scale(int descriptor) {
    return SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_DECIMAL
        ? SqlTypeDescriptor.parameterTwo(descriptor) : 0;
  }
}
