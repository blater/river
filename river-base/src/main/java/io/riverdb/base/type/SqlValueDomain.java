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
      case SqlTypeDescriptor.TYPE_ID_BIGINT -> true;
      case SqlTypeDescriptor.TYPE_ID_BOOLEAN -> value == 0 || value == 1;
      case SqlTypeDescriptor.TYPE_ID_DECIMAL -> ExactDecimal.fits(value, precision);
      case SqlTypeDescriptor.TYPE_ID_DATE -> LocalTemporal.validDate(value);
      case SqlTypeDescriptor.TYPE_ID_TIME -> LocalTemporal.validTime(value, precision);
      case SqlTypeDescriptor.TYPE_ID_TIMESTAMP ->
          LocalTemporal.validTimestamp(value, precision);
      case SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE ->
          LocalTemporal.validInstant(value, precision);
      default -> false;
    };
  }

  /** Smallest physical key that can contain a valid fixed value. */
  public static long minimumFixed(int descriptor) {
    int precision = SqlTypeDescriptor.parameterOne(descriptor);
    return switch (SqlTypeDescriptor.typeId(descriptor)) {
      case SqlTypeDescriptor.TYPE_ID_BIGINT -> Long.MIN_VALUE;
      case SqlTypeDescriptor.TYPE_ID_BOOLEAN, SqlTypeDescriptor.TYPE_ID_TIME -> 0;
      case SqlTypeDescriptor.TYPE_ID_DECIMAL -> -ExactDecimal.powerOfTen(precision) + 1;
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
      case SqlTypeDescriptor.TYPE_ID_BIGINT -> Long.MIN_VALUE;
      case SqlTypeDescriptor.TYPE_ID_BOOLEAN -> 2;
      case SqlTypeDescriptor.TYPE_ID_DECIMAL -> ExactDecimal.powerOfTen(precision);
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
