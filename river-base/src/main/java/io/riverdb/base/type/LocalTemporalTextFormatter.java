package io.riverdb.base.type;

import io.riverdb.base.error.StatusCode;

/** Formats primitive temporal values into declared VARCHAR buffers. */
final class LocalTemporalTextFormatter {
  private LocalTemporalTextFormatter() {
  }

  static StatusCode format(
      long value,
      int sourceDescriptor,
      int targetDescriptor,
      char[] target,
      int offset,
      LocalTemporalCast.TextResult result) {
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
    int precision = LocalTemporalFixedCast.precision(sourceDescriptor);
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

  static int canonicalLength(int descriptor) {
    if (!SqlTypeDescriptor.isValid(descriptor)) {
      return -1;
    }
    int type = SqlTypeDescriptor.typeId(descriptor);
    int precision = LocalTemporalFixedCast.precision(descriptor);
    return switch (type) {
      case SqlTypeDescriptor.TYPE_ID_DATE -> 10;
      case SqlTypeDescriptor.TYPE_ID_TIME -> precision == 0 ? 8 : 9 + precision;
      case SqlTypeDescriptor.TYPE_ID_TIMESTAMP -> precision == 0 ? 19 : 20 + precision;
      case SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE ->
          (precision == 0 ? 19 : 20 + precision) + 6;
      default -> -1;
    };
  }
}
