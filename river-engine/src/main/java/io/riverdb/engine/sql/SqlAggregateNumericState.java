package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.ExactDecimal128;
import io.riverdb.base.type.SqlApproximateNumeric;
import io.riverdb.base.type.SqlNumericTypeRules;
import io.riverdb.base.type.SqlNumericValue;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.sql.SqlAggregateKind;

/** Allocation-free numeric accumulation over caller-owned aggregate lanes. */
final class SqlAggregateNumericState {
  private final ExactDecimal128.Value decimal128 = new ExactDecimal128.Value();
  private final ExactDecimal128.Scratch wideScratch = new ExactDecimal128.Scratch();
  private final SqlAggregateNumericFinish finisher = new SqlAggregateNumericFinish();

  StatusCode accumulate(
      long[] highs,
      long[] values,
      long[] counts,
      boolean[] nulls,
      int slot,
      int kind,
      long high,
      long value,
      int descriptor) {
    if (kind != SqlAggregateKind.SUM && kind != SqlAggregateKind.AVG) {
      return extremum(highs, values, nulls, slot, kind, high, value, descriptor);
    }
    StatusCode status = SqlNumericTypeRules.isApproximate(descriptor)
        ? addApproximate(values, slot, value, descriptor)
        : SqlTypeDescriptor.isWideDecimal(descriptor)
            ? addWide(highs, values, nulls, slot, high, value, descriptor)
            : addCompact(highs, values, slot, value);
    if (!status.isOk()) return status;
    counts[slot]++;
    nulls[slot] = false;
    return StatusCode.OK;
  }

  StatusCode finish(
      SqlBoundAggregateSet aggregates,
      long[] highs,
      long[] values,
      long[] counts,
      boolean[] nulls) {
    return finisher.finish(aggregates, highs, values, counts, nulls);
  }

  private StatusCode addApproximate(
      long[] values, int slot, long value, int descriptor) {
    double sum = Double.longBitsToDouble(values[slot])
        + SqlNumericValue.doubleValue(value, descriptor);
    if (!Double.isFinite(sum)) return StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
    values[slot] = SqlApproximateNumeric.doubleBits(sum);
    return StatusCode.OK;
  }

  private StatusCode addWide(
      long[] highs,
      long[] values,
      boolean[] nulls,
      int slot,
      long high,
      long value,
      int descriptor) {
    if (nulls[slot]) {
      highs[slot] = high;
      values[slot] = value;
      return StatusCode.OK;
    }
    int scale = SqlTypeDescriptor.parameterTwo(descriptor);
    StatusCode status = ExactDecimal128.add(
        highs[slot], values[slot], SqlTypeDescriptor.MAXIMUM_DECIMAL_PRECISION, scale,
        high, value, SqlTypeDescriptor.parameterOne(descriptor), scale, false,
        SqlTypeDescriptor.MAXIMUM_DECIMAL_PRECISION, scale, decimal128, wideScratch);
    if (status.isOk()) {
      highs[slot] = decimal128.high;
      values[slot] = decimal128.low;
    }
    return status;
  }

  private static StatusCode addCompact(
      long[] highs, long[] values, int slot, long value) {
    long previous = values[slot];
    values[slot] += value;
    highs[slot] += (value < 0 ? -1 : 0)
        + (Long.compareUnsigned(values[slot], previous) < 0 ? 1 : 0);
    return StatusCode.OK;
  }

  private StatusCode extremum(
      long[] highs,
      long[] values,
      boolean[] nulls,
      int slot,
      int kind,
      long high,
      long value,
      int descriptor) {
    int compared = nulls[slot] ? 0 : SqlTypeDescriptor.isWideDecimal(descriptor)
        ? ExactDecimal128.compare(
            high, value, scale(descriptor),
            highs[slot], values[slot], scale(descriptor), wideScratch)
        : SqlNumericValue.compare(value, descriptor, values[slot], descriptor);
    if (nulls[slot]
        || kind == SqlAggregateKind.MIN && compared < 0
        || kind == SqlAggregateKind.MAX && compared > 0) {
      highs[slot] = high;
      values[slot] = value;
    }
    nulls[slot] = false;
    return StatusCode.OK;
  }

  private static int scale(int descriptor) {
    return SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_DECIMAL
        ? SqlTypeDescriptor.parameterTwo(descriptor) : 0;
  }
}
