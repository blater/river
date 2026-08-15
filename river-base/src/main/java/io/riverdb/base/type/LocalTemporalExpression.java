package io.riverdb.base.type;

import io.riverdb.base.error.StatusCode;

/** Primitive temporal field and whole-day arithmetic used by scalar execution. */
final class LocalTemporalExpression {
  private LocalTemporalExpression() {
  }

  static StatusCode addDateDays(
      long epochDay, long days, LocalTemporal.Value result) {
    if (result == null || !LocalTemporal.validDate(epochDay)
        || days < LocalTemporal.MINIMUM_EPOCH_DAY - epochDay
        || days > LocalTemporal.MAXIMUM_EPOCH_DAY - epochDay) {
      return StatusCode.DATETIME_FIELD_OVERFLOW;
    }
    result.value = epochDay + days;
    return StatusCode.OK;
  }

  static StatusCode subtractDateDays(
      long epochDay, long days, LocalTemporal.Value result) {
    if (result == null || !LocalTemporal.validDate(epochDay)
        || days < epochDay - LocalTemporal.MAXIMUM_EPOCH_DAY
        || days > epochDay - LocalTemporal.MINIMUM_EPOCH_DAY) {
      return StatusCode.DATETIME_FIELD_OVERFLOW;
    }
    result.value = epochDay - days;
    return StatusCode.OK;
  }

  static StatusCode subtractDates(
      long left, long right, LocalTemporal.Value result) {
    if (result == null
        || !LocalTemporal.validDate(left)
        || !LocalTemporal.validDate(right)) {
      return StatusCode.DATETIME_FIELD_OVERFLOW;
    }
    result.value = left - right;
    return StatusCode.OK;
  }

  static StatusCode extract(
      long source, int descriptor, int field, LocalTemporal.Value result) {
    if (result == null || !SqlTypeDescriptor.isValid(descriptor)) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    int type = SqlTypeDescriptor.typeId(descriptor);
    int precision = SqlTypeDescriptor.parameterOne(descriptor);
    StatusCode status = validSource(source, type, precision);
    if (!status.isOk()) return status;
    if (field == LocalTemporal.EXTRACT_TIMEZONE_HOUR
        || field == LocalTemporal.EXTRACT_TIMEZONE_MINUTE) {
      result.value = 0;
      return type == SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE
          ? StatusCode.OK : StatusCode.DATATYPE_MISMATCH;
    }
    if (field >= LocalTemporal.EXTRACT_YEAR && field <= LocalTemporal.EXTRACT_DAY) {
      return extractDate(source, type, field, result);
    }
    return extractTime(source, type, precision, field, result);
  }

  private static StatusCode validSource(long source, int type, int precision) {
    boolean valid = switch (type) {
      case SqlTypeDescriptor.TYPE_ID_DATE -> LocalTemporal.validDate(source);
      case SqlTypeDescriptor.TYPE_ID_TIME -> LocalTemporal.validTime(source, precision);
      case SqlTypeDescriptor.TYPE_ID_TIMESTAMP ->
          LocalTemporal.validTimestamp(source, precision);
      case SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE ->
          LocalTemporal.validInstant(source, precision);
      default -> false;
    };
    if (valid) return StatusCode.OK;
    return type >= SqlTypeDescriptor.TYPE_ID_DATE
            && type <= SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE
        ? StatusCode.DATETIME_FIELD_OVERFLOW : StatusCode.DATATYPE_MISMATCH;
  }

  private static StatusCode extractDate(
      long source, int type, int field, LocalTemporal.Value result) {
    if (type == SqlTypeDescriptor.TYPE_ID_TIME) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    long epochDay = type == SqlTypeDescriptor.TYPE_ID_DATE
        ? source : Math.floorDiv(source, LocalTemporal.MICROSECONDS_PER_DAY);
    long parts = LocalTemporal.dateParts(epochDay);
    result.value = switch (field) {
      case LocalTemporal.EXTRACT_YEAR -> parts >>> 32;
      case LocalTemporal.EXTRACT_MONTH -> parts >>> 16 & 0xffffL;
      default -> parts & 0xffffL;
    };
    return StatusCode.OK;
  }

  private static StatusCode extractTime(
      long source,
      int type,
      int precision,
      int field,
      LocalTemporal.Value result) {
    if (type == SqlTypeDescriptor.TYPE_ID_DATE
        || field < LocalTemporal.EXTRACT_HOUR
        || field > LocalTemporal.EXTRACT_SECOND) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    long clock = type == SqlTypeDescriptor.TYPE_ID_TIME
        ? source : Math.floorMod(source, LocalTemporal.MICROSECONDS_PER_DAY);
    result.value = switch (field) {
      case LocalTemporal.EXTRACT_HOUR ->
          clock / (3_600L * LocalTemporal.MICROSECONDS_PER_SECOND);
      case LocalTemporal.EXTRACT_MINUTE ->
          clock / (60L * LocalTemporal.MICROSECONDS_PER_SECOND) % 60;
      default -> Math.floorMod(
          clock, 60L * LocalTemporal.MICROSECONDS_PER_SECOND)
              / LocalTemporal.precisionQuantum(precision);
    };
    return StatusCode.OK;
  }
}
