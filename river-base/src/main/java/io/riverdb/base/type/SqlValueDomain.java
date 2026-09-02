package io.riverdb.base.type;

/** Canonical validation for fixed-width SQL values crossing a trust boundary. */
public final class SqlValueDomain {
  private SqlValueDomain() {
  }

  public static boolean validFixed(int descriptor, long value) {
    if (!SqlTypeDescriptor.isValid(descriptor)) {
      return false;
    }
    int precision = SqlTypeDescriptor.parameterOne(descriptor);
    return switch (SqlTypeDescriptor.typeId(descriptor)) {
      case SqlTypeDescriptor.TYPE_ID_SMALLINT -> value >= Short.MIN_VALUE && value <= Short.MAX_VALUE;
      case SqlTypeDescriptor.TYPE_ID_INTEGER -> value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE;
      case SqlTypeDescriptor.TYPE_ID_BIGINT -> true;
      case SqlTypeDescriptor.TYPE_ID_REAL -> SqlApproximateNumeric.validRealBits(value);
      case SqlTypeDescriptor.TYPE_ID_DOUBLE -> SqlApproximateNumeric.validDoubleBits(value);
      case SqlTypeDescriptor.TYPE_ID_BOOLEAN -> value == 0 || value == 1;
      case SqlTypeDescriptor.TYPE_ID_DECIMAL ->
          precision <= SqlTypeDescriptor.MAXIMUM_COMPACT_DECIMAL_PRECISION
              && ExactDecimal.fits(value, precision);
      case SqlTypeDescriptor.TYPE_ID_DATE -> LocalTemporal.validDate(value);
      case SqlTypeDescriptor.TYPE_ID_TIME -> LocalTemporal.validTime(value, precision);
      case SqlTypeDescriptor.TYPE_ID_TIMESTAMP ->
          LocalTemporal.validTimestamp(value, precision);
      case SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE ->
          LocalTemporal.validInstant(value, precision);
      default -> false;
    };
  }

  /** Validates the signed two-long unscaled representation of one DECIMAL value. */
  public static boolean validDecimal128(int descriptor, long high, long low) {
    return SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_DECIMAL
        && SqlTypeDescriptor.isValid(descriptor)
        && ExactDecimal128.fits(
            high, low, SqlTypeDescriptor.parameterOne(descriptor));
  }

  /** Smallest long-lane value, or {@link Long#MAX_VALUE} for a two-lane decimal. */
  public static long minimumFixed(int descriptor) {
    int precision = SqlTypeDescriptor.parameterOne(descriptor);
    return switch (SqlTypeDescriptor.typeId(descriptor)) {
      case SqlTypeDescriptor.TYPE_ID_SMALLINT -> Short.MIN_VALUE;
      case SqlTypeDescriptor.TYPE_ID_INTEGER -> Integer.MIN_VALUE;
      case SqlTypeDescriptor.TYPE_ID_BIGINT -> Long.MIN_VALUE;
      case SqlTypeDescriptor.TYPE_ID_BOOLEAN, SqlTypeDescriptor.TYPE_ID_TIME -> 0;
      case SqlTypeDescriptor.TYPE_ID_DECIMAL ->
          precision <= SqlTypeDescriptor.MAXIMUM_COMPACT_DECIMAL_PRECISION
              ? -ExactDecimal.powerOfTen(precision) + 1 : Long.MAX_VALUE;
      case SqlTypeDescriptor.TYPE_ID_DATE -> LocalTemporal.MINIMUM_EPOCH_DAY;
      case SqlTypeDescriptor.TYPE_ID_TIMESTAMP ->
          LocalTemporal.MINIMUM_TIMESTAMP_MICROSECONDS;
      case SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE ->
          LocalTemporal.MINIMUM_INSTANT_MICROSECONDS;
      default -> Long.MAX_VALUE;
    };
  }

  /** Exclusive key above every valid fixed value, or Long.MIN_VALUE if unavailable. */
  public static long exclusiveMaximumFixed(int descriptor) {
    int precision = SqlTypeDescriptor.parameterOne(descriptor);
    return switch (SqlTypeDescriptor.typeId(descriptor)) {
      case SqlTypeDescriptor.TYPE_ID_SMALLINT -> (long) Short.MAX_VALUE + 1;
      case SqlTypeDescriptor.TYPE_ID_INTEGER -> (long) Integer.MAX_VALUE + 1;
      case SqlTypeDescriptor.TYPE_ID_BIGINT -> Long.MIN_VALUE;
      case SqlTypeDescriptor.TYPE_ID_BOOLEAN -> 2;
      case SqlTypeDescriptor.TYPE_ID_DECIMAL ->
          precision <= SqlTypeDescriptor.MAXIMUM_COMPACT_DECIMAL_PRECISION
              ? ExactDecimal.powerOfTen(precision) : Long.MIN_VALUE;
      case SqlTypeDescriptor.TYPE_ID_DATE -> LocalTemporal.MAXIMUM_EPOCH_DAY + 1;
      case SqlTypeDescriptor.TYPE_ID_TIME -> LocalTemporal.MICROSECONDS_PER_DAY;
      case SqlTypeDescriptor.TYPE_ID_TIMESTAMP ->
          LocalTemporal.MAXIMUM_TIMESTAMP_MICROSECONDS + 1;
      case SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE ->
          LocalTemporal.MAXIMUM_INSTANT_MICROSECONDS + 1;
      default -> Long.MIN_VALUE;
    };
  }
}
