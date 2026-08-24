package io.riverdb.base.type;

import io.riverdb.base.error.StatusCode;

/** Performs allocation-free casts between fixed temporal representations. */
final class LocalTemporalFixedCast {
  private LocalTemporalFixedCast() {
  }

  static StatusCode cast(
      long value,
      int sourceDescriptor,
      int targetDescriptor,
      LocalTemporal.Value result) {
    if (result == null
        || !SqlTypeDescriptor.isValid(sourceDescriptor)
        || !SqlTypeDescriptor.isValid(targetDescriptor)) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    int sourceType = SqlTypeDescriptor.typeId(sourceDescriptor);
    int targetType = SqlTypeDescriptor.typeId(targetDescriptor);
    if (!temporal(sourceType) || !temporal(targetType)) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    if (!validAtTargetPrecision(value, sourceDescriptor)) {
      return StatusCode.DATETIME_FIELD_OVERFLOW;
    }
    if (sourceType == targetType) {
      if (!validAtTargetPrecision(value, targetDescriptor)) {
        return StatusCode.DATETIME_FIELD_OVERFLOW;
      }
      result.value = value;
      result.precision = precision(targetDescriptor);
      result.offsetMinutes = 0;
      return StatusCode.OK;
    }
    if (sourceType == SqlTypeDescriptor.TYPE_ID_DATE
        && targetType == SqlTypeDescriptor.TYPE_ID_TIMESTAMP) {
      result.value = value * LocalTemporal.MICROSECONDS_PER_DAY;
      result.precision = precision(targetDescriptor);
      result.offsetMinutes = 0;
      return LocalTemporal.validDate(value)
              && LocalTemporal.validTimestamp(result.value, result.precision)
          ? StatusCode.OK : StatusCode.DATETIME_FIELD_OVERFLOW;
    }
    if (sourceType == SqlTypeDescriptor.TYPE_ID_TIMESTAMP
        && targetType == SqlTypeDescriptor.TYPE_ID_DATE) {
      if (!LocalTemporal.validTimestamp(value, precision(sourceDescriptor))
          || Math.floorMod(value, LocalTemporal.MICROSECONDS_PER_DAY) != 0) {
        return StatusCode.DATETIME_FIELD_OVERFLOW;
      }
      result.value = Math.floorDiv(value, LocalTemporal.MICROSECONDS_PER_DAY);
      result.precision = 0;
      result.offsetMinutes = 0;
      return StatusCode.OK;
    }
    return sourceType == SqlTypeDescriptor.TYPE_ID_TIMESTAMP
            && targetType == SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE
        || sourceType == SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE
            && targetType == SqlTypeDescriptor.TYPE_ID_TIMESTAMP
        ? StatusCode.FEATURE_NOT_SUPPORTED : StatusCode.DATATYPE_MISMATCH;
  }

  private static boolean validAtTargetPrecision(long value, int descriptor) {
    int precision = precision(descriptor);
    return switch (SqlTypeDescriptor.typeId(descriptor)) {
      case SqlTypeDescriptor.TYPE_ID_DATE -> LocalTemporal.validDate(value);
      case SqlTypeDescriptor.TYPE_ID_TIME -> LocalTemporal.validTime(value, precision);
      case SqlTypeDescriptor.TYPE_ID_TIMESTAMP ->
          LocalTemporal.validTimestamp(value, precision);
      case SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE ->
          LocalTemporal.validInstant(value, precision);
      default -> false;
    };
  }

  static int precision(int descriptor) {
    int type = SqlTypeDescriptor.typeId(descriptor);
    return type == SqlTypeDescriptor.TYPE_ID_TIME
            || type == SqlTypeDescriptor.TYPE_ID_TIMESTAMP
            || type == SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE
        ? SqlTypeDescriptor.parameterOne(descriptor) : 0;
  }

  private static boolean temporal(int type) {
    return type >= SqlTypeDescriptor.TYPE_ID_DATE
        && type <= SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE;
  }
}
