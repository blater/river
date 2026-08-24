package io.riverdb.base.type;

import io.riverdb.base.error.StatusCode;

/** Parses ISO date and local timestamp portions. */
final class LocalTemporalDateParser {
  private LocalTemporalDateParser() { }

  static StatusCode date(CharSequence text, int start, int end, LocalTemporal.Value result) {
    if (text == null || result == null || end - start != 10 || character(text, start + 4) != '-'
        || character(text, start + 7) != '-') return StatusCode.INVALID_DATETIME_FORMAT;
    int year = digits(text, start, 4), month = digits(text, start + 5, 2), day = digits(text, start + 8, 2);
    if (year < 0 || month < 0 || day < 0) return StatusCode.INVALID_DATETIME_FORMAT;
    if (!LocalTemporal.validDatePartsForParser(year, month, day)) return StatusCode.DATETIME_FIELD_OVERFLOW;
    result.value = LocalTemporal.epochDayForParser(year, month, day);
    result.precision = 0;
    result.offsetMinutes = 0;
    return StatusCode.OK;
  }

  static StatusCode timestamp(CharSequence text, int start, int end, LocalTemporal.Value result) {
    if (text == null || result == null || end - start < 19 || character(text, start + 10) != ' ') {
      return StatusCode.INVALID_DATETIME_FORMAT;
    }
    StatusCode status = date(text, start, start + 10, result);
    if (!status.isOk()) return status;
    long day = result.value;
    status = LocalTemporalClockParser.time(text, start + 11, end, result);
    if (!status.isOk()) return status;
    result.value += day * LocalTemporal.MICROSECONDS_PER_DAY;
    return StatusCode.OK;
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
