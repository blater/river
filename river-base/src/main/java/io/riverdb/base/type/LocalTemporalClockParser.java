package io.riverdb.base.type;

import io.riverdb.base.error.StatusCode;

/** Parses ISO local time and timezone-offset portions. */
final class LocalTemporalClockParser {
  private LocalTemporalClockParser() { }

  static StatusCode time(CharSequence text, int start, int end, LocalTemporal.Value result) {
    if (text == null || result == null || end - start < 8 || character(text, start + 2) != ':'
        || character(text, start + 5) != ':') return StatusCode.INVALID_DATETIME_FORMAT;
    int hour = digits(text, start, 2), minute = digits(text, start + 3, 2), second = digits(text, start + 6, 2);
    if (hour < 0 || minute < 0 || second < 0) return StatusCode.INVALID_DATETIME_FORMAT;
    if (hour > 23 || minute > 59 || second > 59) return StatusCode.DATETIME_FIELD_OVERFLOW;
    int precision = 0, fraction = 0;
    if (end != start + 8) {
      if (character(text, start + 8) != '.') return StatusCode.INVALID_DATETIME_FORMAT;
      precision = end - start - 9;
      if (!LocalTemporal.validPrecisionForParser(precision) || precision == 0) return StatusCode.INVALID_DATETIME_FORMAT;
      fraction = digits(text, start + 9, precision);
      if (fraction < 0) return StatusCode.INVALID_DATETIME_FORMAT;
    }
    result.value = ((hour * 60L + minute) * 60L + second) * LocalTemporal.MICROSECONDS_PER_SECOND
        + fraction * LocalTemporal.precisionQuantum(precision);
    result.precision = precision;
    result.offsetMinutes = 0;
    return StatusCode.OK;
  }

  static StatusCode timestampWithOffset(CharSequence text, int start, int end,
      LocalTemporal.Value result) {
    int offsetStart = end - 6;
    if (text == null || result == null || offsetStart - start < 19) return StatusCode.INVALID_DATETIME_FORMAT;
    StatusCode status = LocalTemporalDateParser.timestamp(text, start, offsetStart, result);
    if (!status.isOk()) return status;
    char sign = character(text, offsetStart);
    int hours = digits(text, offsetStart + 1, 2), minutes = digits(text, offsetStart + 4, 2);
    if ((sign != '+' && sign != '-') || character(text, offsetStart + 3) != ':'
        || hours < 0 || minutes < 0) return StatusCode.INVALID_DATETIME_FORMAT;
    if (hours > 14 || minutes > 59 || hours == 14 && minutes != 0) {
      return StatusCode.INVALID_TIME_ZONE_DISPLACEMENT;
    }
    int offsetMinutes = hours * 60 + minutes;
    if (sign == '-') offsetMinutes = -offsetMinutes;
    result.value -= offsetMinutes * 60L * LocalTemporal.MICROSECONDS_PER_SECOND;
    result.offsetMinutes = offsetMinutes;
    return LocalTemporal.validInstant(result.value, result.precision)
        ? StatusCode.OK : StatusCode.DATETIME_FIELD_OVERFLOW;
  }

  private static int digits(CharSequence text, int offset, int count) {
    if (offset < 0 || count < 1 || offset > text.length() - count) return -1;
    int value = 0;
    for (int index = 0; index < count; index++) {
      char character = text.charAt(offset + index);
      if (character < '0' || character > '9') return -1;
      value = value * 10 + character - '0';
    }
    return value;
  }

  private static char character(CharSequence text, int index) {
    return index >= 0 && index < text.length() ? text.charAt(index) : 0;
  }
}
