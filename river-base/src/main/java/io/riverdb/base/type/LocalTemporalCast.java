package io.riverdb.base.type;

import io.riverdb.base.error.StatusCode;

/** Allocation-free casts between River's primitive temporal values and canonical text. */
public final class LocalTemporalCast {
  public static final int MAXIMUM_TEXT_CHARACTERS = 32;

  private LocalTemporalCast() {
  }

  /** Casts a fixed-width temporal value without applying a session time zone. */
  public static StatusCode castFixed(
      long value, int sourceDescriptor, int targetDescriptor, LocalTemporal.Value result) {
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

  /** Parses a complete canonical temporal string into the requested target descriptor. */
  public static StatusCode parseText(
      CharSequence text, int start, int end, int targetDescriptor,
      LocalTemporal.Value result) {
    if (text == null
        || result == null
        || start < 0
        || end < start
        || end > text.length()
        || !SqlTypeDescriptor.isValid(targetDescriptor)) {
      return StatusCode.INVALID_DATETIME_FORMAT;
    }
    int targetType = SqlTypeDescriptor.typeId(targetDescriptor);
    StatusCode status = switch (targetType) {
      case SqlTypeDescriptor.TYPE_ID_DATE ->
          LocalTemporal.parseDateStatus(text, start, end, result);
      case SqlTypeDescriptor.TYPE_ID_TIME ->
          LocalTemporal.parseTimeStatus(text, start, end, result);
      case SqlTypeDescriptor.TYPE_ID_TIMESTAMP ->
          LocalTemporal.parseTimestampStatus(text, start, end, result);
      case SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE ->
          LocalTemporal.parseTimestampWithOffsetStatus(text, start, end, result);
      default -> StatusCode.DATATYPE_MISMATCH;
    };
    if (!status.isOk() || targetType == SqlTypeDescriptor.TYPE_ID_DATE) {
      return status;
    }
    int targetPrecision = precision(targetDescriptor);
    if (result.precision > targetPrecision
        || !validAtTargetPrecision(result.value, targetDescriptor)) {
      return StatusCode.DATETIME_FIELD_OVERFLOW;
    }
    result.precision = targetPrecision;
    return StatusCode.OK;
  }

  /** Formats one primitive temporal value into the target VARCHAR declaration. */
  public static StatusCode formatText(
      long value,
      int sourceDescriptor,
      int targetDescriptor,
      char[] target,
      int offset,
      TextResult result) {
    if (result != null) {
      result.length = 0;
    }
    if (result == null
        || target == null
        || offset < 0
        || !SqlTypeDescriptor.isValid(sourceDescriptor)
        || SqlTypeDescriptor.typeId(targetDescriptor) != SqlTypeDescriptor.TYPE_ID_VARCHAR
        || !SqlTypeDescriptor.isValid(targetDescriptor)) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    int length = canonicalLength(sourceDescriptor);
    if (length < 0) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    if (length > SqlTypeDescriptor.parameterOne(targetDescriptor)) {
      return StatusCode.STRING_DATA_RIGHT_TRUNCATION;
    }
    if (offset > target.length - length) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    int sourceType = SqlTypeDescriptor.typeId(sourceDescriptor);
    int precision = precision(sourceDescriptor);
    int written = switch (sourceType) {
      case SqlTypeDescriptor.TYPE_ID_DATE -> LocalTemporal.formatDate(value, target, offset);
      case SqlTypeDescriptor.TYPE_ID_TIME ->
          LocalTemporal.formatTime(value, precision, target, offset);
      case SqlTypeDescriptor.TYPE_ID_TIMESTAMP ->
          LocalTemporal.formatTimestamp(value, precision, target, offset);
      case SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE ->
          LocalTemporal.formatTimestampWithOffset(value, precision, 0, target, offset);
      default -> -1;
    };
    if (written < 0) {
      return StatusCode.DATETIME_FIELD_OVERFLOW;
    }
    result.length = written;
    return StatusCode.OK;
  }

  public static int canonicalLength(int descriptor) {
    if (!SqlTypeDescriptor.isValid(descriptor)) {
      return -1;
    }
    int type = SqlTypeDescriptor.typeId(descriptor);
    int precision = precision(descriptor);
    return switch (type) {
      case SqlTypeDescriptor.TYPE_ID_DATE -> 10;
      case SqlTypeDescriptor.TYPE_ID_TIME -> precision == 0 ? 8 : 9 + precision;
      case SqlTypeDescriptor.TYPE_ID_TIMESTAMP -> precision == 0 ? 19 : 20 + precision;
      case SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE ->
          (precision == 0 ? 19 : 20 + precision) + 6;
      default -> -1;
    };
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

  private static int precision(int descriptor) {
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

  /** Reusable formatted-text carrier. */
  public static final class TextResult {
    public int length;
  }
}
