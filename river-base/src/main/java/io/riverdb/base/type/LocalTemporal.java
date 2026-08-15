package io.riverdb.base.type;

import io.riverdb.base.error.StatusCode;

/** Allocation-free codec for River's primitive local and instant temporal representations. */
public final class LocalTemporal {
  public static final long MICROSECONDS_PER_SECOND = 1_000_000L;
  public static final long MICROSECONDS_PER_DAY = 86_400L * MICROSECONDS_PER_SECOND;
  public static final long MINIMUM_EPOCH_DAY = -719_162L;
  public static final long MAXIMUM_EPOCH_DAY = 2_932_896L;
  public static final long MINIMUM_TIMESTAMP_MICROSECONDS =
      MINIMUM_EPOCH_DAY * MICROSECONDS_PER_DAY;
  public static final long MAXIMUM_TIMESTAMP_MICROSECONDS =
      (MAXIMUM_EPOCH_DAY + 1) * MICROSECONDS_PER_DAY - 1;
  public static final int MAXIMUM_OFFSET_MINUTES = 14 * 60;
  public static final long MINIMUM_INSTANT_MICROSECONDS =
      MINIMUM_TIMESTAMP_MICROSECONDS;
  public static final long MAXIMUM_INSTANT_MICROSECONDS =
      MAXIMUM_TIMESTAMP_MICROSECONDS;
  public static final int EXTRACT_YEAR = 1;
  public static final int EXTRACT_MONTH = 2;
  public static final int EXTRACT_DAY = 3;
  public static final int EXTRACT_HOUR = 4;
  public static final int EXTRACT_MINUTE = 5;
  public static final int EXTRACT_SECOND = 6;
  public static final int EXTRACT_TIMEZONE_HOUR = 7;
  public static final int EXTRACT_TIMEZONE_MINUTE = 8;

  private static final long[] PRECISION_QUANTA = {
      1_000_000L, 100_000L, 10_000L, 1_000L, 100L, 10L, 1L
  };

  private LocalTemporal() {
  }

  public static boolean validDate(long epochDay) {
    return epochDay >= MINIMUM_EPOCH_DAY && epochDay <= MAXIMUM_EPOCH_DAY;
  }

  public static boolean validTime(long microseconds, int precision) {
    return validPrecision(precision)
        && microseconds >= 0
        && microseconds < MICROSECONDS_PER_DAY
        && microseconds % PRECISION_QUANTA[precision] == 0;
  }

  public static boolean validTimestamp(long microseconds, int precision) {
    return validPrecision(precision)
        && microseconds >= MINIMUM_TIMESTAMP_MICROSECONDS
        && microseconds <= MAXIMUM_TIMESTAMP_MICROSECONDS
        && microseconds % PRECISION_QUANTA[precision] == 0;
  }

  public static boolean validInstant(long microseconds, int precision) {
    return validPrecision(precision)
        && microseconds >= MINIMUM_INSTANT_MICROSECONDS
        && microseconds <= MAXIMUM_INSTANT_MICROSECONDS
        && microseconds % PRECISION_QUANTA[precision] == 0;
  }

  public static long truncateToPrecision(long microseconds, int precision) {
    if (!validPrecision(precision)) {
      return Long.MIN_VALUE;
    }
    long quantum = PRECISION_QUANTA[precision];
    return Math.floorDiv(microseconds, quantum) * quantum;
  }

  public static StatusCode addDateDays(long epochDay, long days, Value result) {
    return LocalTemporalExpression.addDateDays(epochDay, days, result);
  }

  public static StatusCode subtractDateDays(long epochDay, long days, Value result) {
    return LocalTemporalExpression.subtractDateDays(epochDay, days, result);
  }

  public static StatusCode subtractDates(long left, long right, Value result) {
    return LocalTemporalExpression.subtractDates(left, right, result);
  }

  public static StatusCode extract(
      long source, int descriptor, int field, Value result) {
    return LocalTemporalExpression.extract(source, descriptor, field, result);
  }

  public static boolean parseDate(
      CharSequence text, int start, int end, Value result) {
    return parseDateStatus(text, start, end, result).isOk();
  }

  public static StatusCode parseDateStatus(
      CharSequence text, int start, int end, Value result) {
    if (text == null || result == null || end - start != 10
        || character(text, start + 4) != '-'
        || character(text, start + 7) != '-') {
      return StatusCode.INVALID_DATETIME_FORMAT;
    }
    int year = digits(text, start, 4);
    int month = digits(text, start + 5, 2);
    int day = digits(text, start + 8, 2);
    if (year < 0 || month < 0 || day < 0) {
      return StatusCode.INVALID_DATETIME_FORMAT;
    }
    if (!validDateParts(year, month, day)) {
      return StatusCode.DATETIME_FIELD_OVERFLOW;
    }
    result.value = epochDay(year, month, day);
    result.precision = 0;
    result.offsetMinutes = 0;
    return StatusCode.OK;
  }

  public static boolean parseTime(
      CharSequence text, int start, int end, Value result) {
    return parseTimeStatus(text, start, end, result).isOk();
  }

  public static StatusCode parseTimeStatus(
      CharSequence text, int start, int end, Value result) {
    if (text == null || result == null || end - start < 8
        || character(text, start + 2) != ':'
        || character(text, start + 5) != ':') {
      return StatusCode.INVALID_DATETIME_FORMAT;
    }
    int hour = digits(text, start, 2);
    int minute = digits(text, start + 3, 2);
    int second = digits(text, start + 6, 2);
    if (hour < 0 || minute < 0 || second < 0) {
      return StatusCode.INVALID_DATETIME_FORMAT;
    }
    if (hour > 23 || minute > 59 || second > 59) {
      return StatusCode.DATETIME_FIELD_OVERFLOW;
    }
    int precision = 0;
    int fraction = 0;
    if (end != start + 8) {
      if (character(text, start + 8) != '.') {
        return StatusCode.INVALID_DATETIME_FORMAT;
      }
      precision = end - start - 9;
      if (!validPrecision(precision) || precision == 0) {
        return StatusCode.INVALID_DATETIME_FORMAT;
      }
      fraction = digits(text, start + 9, precision);
      if (fraction < 0) {
        return StatusCode.INVALID_DATETIME_FORMAT;
      }
    }
    result.value = ((hour * 60L + minute) * 60L + second)
        * MICROSECONDS_PER_SECOND
        + fraction * PRECISION_QUANTA[precision];
    result.precision = precision;
    result.offsetMinutes = 0;
    return StatusCode.OK;
  }

  public static boolean parseTimestamp(
      CharSequence text, int start, int end, Value result) {
    return parseTimestampStatus(text, start, end, result).isOk();
  }

  public static StatusCode parseTimestampStatus(
      CharSequence text, int start, int end, Value result) {
    if (text == null || result == null || end - start < 19
        || character(text, start + 10) != ' ') {
      return StatusCode.INVALID_DATETIME_FORMAT;
    }
    StatusCode status = parseDateStatus(text, start, start + 10, result);
    if (!status.isOk()) {
      return status;
    }
    long day = result.value;
    status = parseTimeStatus(text, start + 11, end, result);
    if (!status.isOk()) {
      return status;
    }
    result.value += day * MICROSECONDS_PER_DAY;
    return StatusCode.OK;
  }

  public static boolean parseTimestampWithOffset(
      CharSequence text, int start, int end, Value result) {
    return parseTimestampWithOffsetStatus(text, start, end, result).isOk();
  }

  public static StatusCode parseTimestampWithOffsetStatus(
      CharSequence text, int start, int end, Value result) {
    int offsetStart = end - 6;
    if (text == null || result == null || offsetStart - start < 19) {
      return StatusCode.INVALID_DATETIME_FORMAT;
    }
    StatusCode localStatus = parseTimestampStatus(text, start, offsetStart, result);
    if (!localStatus.isOk()) {
      return localStatus;
    }
    char sign = character(text, offsetStart);
    int hours = digits(text, offsetStart + 1, 2);
    int minutes = digits(text, offsetStart + 4, 2);
    if ((sign != '+' && sign != '-')
        || character(text, offsetStart + 3) != ':'
        || hours < 0 || minutes < 0) {
      return StatusCode.INVALID_DATETIME_FORMAT;
    }
    if (hours > 14
        || minutes > 59
        || hours == 14 && minutes != 0) {
      return StatusCode.INVALID_TIME_ZONE_DISPLACEMENT;
    }
    int offsetMinutes = hours * 60 + minutes;
    if (sign == '-') {
      offsetMinutes = -offsetMinutes;
    }
    result.value -= offsetMinutes * 60L * MICROSECONDS_PER_SECOND;
    result.offsetMinutes = offsetMinutes;
    return validInstant(result.value, result.precision)
        ? StatusCode.OK : StatusCode.DATETIME_FIELD_OVERFLOW;
  }

  public static int formatDate(long epochDay, char[] target, int offset) {
    if (!validTarget(target, offset, 10) || !validDate(epochDay)) {
      return -1;
    }
    long parts = dateParts(epochDay);
    int year = (int) (parts >>> 32);
    int month = (int) (parts >>> 16 & 0xffffL);
    int day = (int) (parts & 0xffffL);
    fourDigits(target, offset, year);
    target[offset + 4] = '-';
    twoDigits(target, offset + 5, month);
    target[offset + 7] = '-';
    twoDigits(target, offset + 8, day);
    return 10;
  }

  static long dateParts(long epochDay) {
    long zeroDay = epochDay + 719_468L;
    long era = zeroDay / 146_097L;
    int dayOfEra = (int) (zeroDay - era * 146_097L);
    int yearOfEra = (dayOfEra - dayOfEra / 1_460 + dayOfEra / 36_524
        - dayOfEra / 146_096) / 365;
    int year = (int) era * 400 + yearOfEra;
    int dayOfYear = dayOfEra
        - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100);
    int monthPrime = (5 * dayOfYear + 2) / 153;
    int day = dayOfYear - (153 * monthPrime + 2) / 5 + 1;
    int month = monthPrime + (monthPrime < 10 ? 3 : -9);
    year += month <= 2 ? 1 : 0;
    return (long) year << 32 | (long) month << 16 | day;
  }

  static long precisionQuantum(int precision) {
    return validPrecision(precision) ? PRECISION_QUANTA[precision] : 0;
  }

  public static int formatTime(
      long microseconds, int precision, char[] target, int offset) {
    if (!validPrecision(precision)) {
      return -1;
    }
    int length = precision == 0 ? 8 : 9 + precision;
    if (!validTarget(target, offset, length)
        || !validTime(microseconds, precision)) {
      return -1;
    }
    long seconds = microseconds / MICROSECONDS_PER_SECOND;
    twoDigits(target, offset, (int) (seconds / 3_600));
    target[offset + 2] = ':';
    twoDigits(target, offset + 3, (int) (seconds / 60 % 60));
    target[offset + 5] = ':';
    twoDigits(target, offset + 6, (int) (seconds % 60));
    if (precision > 0) {
      target[offset + 8] = '.';
      int fraction = (int) (microseconds % MICROSECONDS_PER_SECOND);
      for (int index = precision; index < 6; index++) {
        fraction /= 10;
      }
      for (int index = precision - 1; index >= 0; index--) {
        target[offset + 9 + index] = (char) ('0' + fraction % 10);
        fraction /= 10;
      }
    }
    return length;
  }

  public static int formatTimestamp(
      long microseconds, int precision, char[] target, int offset) {
    if (!validPrecision(precision)) {
      return -1;
    }
    int length = precision == 0 ? 19 : 20 + precision;
    if (!validTarget(target, offset, length)
        || !validTimestamp(microseconds, precision)) {
      return -1;
    }
    long epochDay = Math.floorDiv(microseconds, MICROSECONDS_PER_DAY);
    long time = Math.floorMod(microseconds, MICROSECONDS_PER_DAY);
    formatDate(epochDay, target, offset);
    target[offset + 10] = ' ';
    formatTime(time, precision, target, offset + 11);
    return length;
  }

  public static int formatTimestampWithOffset(
      long instantMicroseconds,
      int precision,
      int offsetMinutes,
      char[] target,
      int offset) {
    int timestampLength = precision == 0 ? 19 : 20 + precision;
    if (!validInstant(instantMicroseconds, precision)
        || offsetMinutes < -MAXIMUM_OFFSET_MINUTES
        || offsetMinutes > MAXIMUM_OFFSET_MINUTES
        || !validTarget(target, offset, timestampLength + 6)) {
      return -1;
    }
    long local = instantMicroseconds
        + offsetMinutes * 60L * MICROSECONDS_PER_SECOND;
    if (formatTimestamp(local, precision, target, offset) < 0) {
      return -1;
    }
    int absolute = Math.abs(offsetMinutes);
    target[offset + timestampLength] = offsetMinutes < 0 ? '-' : '+';
    twoDigits(target, offset + timestampLength + 1, absolute / 60);
    target[offset + timestampLength + 3] = ':';
    twoDigits(target, offset + timestampLength + 4, absolute % 60);
    return timestampLength + 6;
  }

  private static long epochDay(int year, int month, int day) {
    int adjustedYear = year - (month <= 2 ? 1 : 0);
    int era = adjustedYear / 400;
    int yearOfEra = adjustedYear - era * 400;
    int monthPrime = month + (month > 2 ? -3 : 9);
    int dayOfYear = (153 * monthPrime + 2) / 5 + day - 1;
    int dayOfEra = yearOfEra * 365 + yearOfEra / 4
        - yearOfEra / 100 + dayOfYear;
    return era * 146_097L + dayOfEra - 719_468L;
  }

  public static int year(long epochDay) {
    return validDate(epochDay) ? (int) (dateParts(epochDay) >>> 32) : 0;
  }

  private static int daysInMonth(int year, int month) {
    return switch (month) {
      case 2 -> leapYear(year) ? 29 : 28;
      case 4, 6, 9, 11 -> 30;
      default -> 31;
    };
  }

  /** ISO-8601 Monday=1 through Sunday=7. */
  private static boolean validDateParts(int year, int month, int day) {
    if (year < 1 || year > 9_999 || month < 1 || month > 12 || day < 1) {
      return false;
    }
    return day <= daysInMonth(year, month);
  }

  private static boolean leapYear(int year) {
    return year % 4 == 0 && (year % 100 != 0 || year % 400 == 0);
  }

  private static int digits(CharSequence text, int offset, int count) {
    if (offset < 0 || count < 1 || offset > text.length() - count) {
      return -1;
    }
    int value = 0;
    for (int index = 0; index < count; index++) {
      char character = text.charAt(offset + index);
      if (character < '0' || character > '9') {
        return -1;
      }
      value = value * 10 + character - '0';
    }
    return value;
  }

  private static char character(CharSequence text, int index) {
    return index >= 0 && index < text.length() ? text.charAt(index) : 0;
  }

  private static boolean validPrecision(int precision) {
    return precision >= 0 && precision <= SqlTypeDescriptor.MAXIMUM_TEMPORAL_PRECISION;
  }

  private static boolean validTarget(char[] target, int offset, int length) {
    return target != null && offset >= 0 && offset <= target.length - length;
  }

  private static void twoDigits(char[] target, int offset, int value) {
    target[offset] = (char) ('0' + value / 10);
    target[offset + 1] = (char) ('0' + value % 10);
  }

  private static void fourDigits(char[] target, int offset, int value) {
    target[offset] = (char) ('0' + value / 1_000);
    target[offset + 1] = (char) ('0' + value / 100 % 10);
    target[offset + 2] = (char) ('0' + value / 10 % 10);
    target[offset + 3] = (char) ('0' + value % 10);
  }

  /** Reusable parse carrier. */
  public static final class Value {
    public long value;
    public int precision;
    public int offsetMinutes;
  }
}
