package io.riverdb.format.btree;

import io.riverdb.base.type.ExactDecimal128;
import io.riverdb.base.type.SqlApproximateNumeric;
import io.riverdb.base.type.SqlNumericTypeRules;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlValueDomain;
import java.nio.ByteBuffer;

/** Canonical domain validation for one present tuple value. */
final class TupleKeyValueValidation {
  private TupleKeyValueValidation() { }

  static boolean valid(ByteBuffer source, int valueOffset, int type) {
    if (type == SqlTypeDescriptor.TYPE_ID_VARCHAR) return true;
    if (type == SqlTypeDescriptor.TYPE_ID_DECIMAL) {
      long high = TupleKeyCodec.getBigEndianLong(source, valueOffset) ^ Long.MIN_VALUE;
      long low = TupleKeyCodec.getBigEndianLong(source, valueOffset + Long.BYTES);
      return ExactDecimal128.fits(
          high, low, SqlTypeDescriptor.MAXIMUM_DECIMAL_PRECISION);
    }
    int descriptor = globalDescriptor(type);
    long encoded = TupleKeyCodec.getBigEndianLong(source, valueOffset);
    long value = SqlNumericTypeRules.isApproximate(descriptor)
        ? SqlApproximateNumeric.valueBits(descriptor, encoded)
        : encoded ^ Long.MIN_VALUE;
    return SqlValueDomain.validFixed(descriptor, value);
  }

  private static int globalDescriptor(int type) {
    return switch (type) {
      case SqlTypeDescriptor.TYPE_ID_BIGINT -> SqlTypeDescriptor.BIGINT;
      case SqlTypeDescriptor.TYPE_ID_BOOLEAN -> SqlTypeDescriptor.BOOLEAN;
      case SqlTypeDescriptor.TYPE_ID_DATE -> SqlTypeDescriptor.DATE;
      case SqlTypeDescriptor.TYPE_ID_TIME ->
          SqlTypeDescriptor.time(SqlTypeDescriptor.MAXIMUM_TEMPORAL_PRECISION);
      case SqlTypeDescriptor.TYPE_ID_TIMESTAMP ->
          SqlTypeDescriptor.timestamp(SqlTypeDescriptor.MAXIMUM_TEMPORAL_PRECISION);
      case SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE ->
          SqlTypeDescriptor.timestampWithTimeZone(SqlTypeDescriptor.MAXIMUM_TEMPORAL_PRECISION);
      case SqlTypeDescriptor.TYPE_ID_SMALLINT -> SqlTypeDescriptor.SMALLINT;
      case SqlTypeDescriptor.TYPE_ID_INTEGER -> SqlTypeDescriptor.INTEGER;
      case SqlTypeDescriptor.TYPE_ID_REAL -> SqlTypeDescriptor.REAL;
      case SqlTypeDescriptor.TYPE_ID_DOUBLE -> SqlTypeDescriptor.DOUBLE;
      default -> 0;
    };
  }
}
