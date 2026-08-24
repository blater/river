package io.riverdb.base.type;

import io.riverdb.base.error.StatusCode;

/** Parses canonical temporal text into reusable primitive values. */
final class LocalTemporalTextParser {
  private LocalTemporalTextParser() {
  }

  static StatusCode parse(
      CharSequence text,
      int start,
      int end,
      int targetDescriptor,
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
      case SqlTypeDescriptor.TYPE_ID_DATE -> LocalTemporal.parseDateStatus(text, start, end, result);
      case SqlTypeDescriptor.TYPE_ID_TIME -> LocalTemporal.parseTimeStatus(text, start, end, result);
      case SqlTypeDescriptor.TYPE_ID_TIMESTAMP ->
          LocalTemporal.parseTimestampStatus(text, start, end, result);
      case SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE ->
          LocalTemporal.parseTimestampWithOffsetStatus(text, start, end, result);
      default -> StatusCode.DATATYPE_MISMATCH;
    };
    if (!status.isOk() || targetType == SqlTypeDescriptor.TYPE_ID_DATE) {
      return status;
    }
    int targetPrecision = LocalTemporalFixedCast.precision(targetDescriptor);
    if (result.precision > targetPrecision
        || !validAtTargetPrecision(result.value, targetDescriptor)) {
      return StatusCode.DATETIME_FIELD_OVERFLOW;
    }
    result.precision = targetPrecision;
    return StatusCode.OK;
  }

  private static boolean validAtTargetPrecision(long value, int descriptor) {
    int precision = LocalTemporalFixedCast.precision(descriptor);
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
}
