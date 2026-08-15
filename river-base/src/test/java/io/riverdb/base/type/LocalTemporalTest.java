package io.riverdb.base.type;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import org.junit.jupiter.api.Test;

final class LocalTemporalTest {
  private final LocalTemporal.Value value = new LocalTemporal.Value();
  private final char[] formatted = new char[32];

  @Test
  void parsesAndFormatsCanonicalLocalValues() {
    assertDate("0001-01-01", LocalTemporal.MINIMUM_EPOCH_DAY);
    assertDate("1970-01-01", 0);
    assertDate("2000-02-29", 11_016);
    assertDate("9999-12-31", LocalTemporal.MAXIMUM_EPOCH_DAY);

    assertTime("00:00:00", 0, 0);
    assertTime("12:34:56.123", 3, 45_296_123_000L);
    assertTime("23:59:59.999999", 6, 86_399_999_999L);

    assertTimestamp("1969-12-31 23:59:59.999999", 6, -1);
    assertTimestamp("1970-01-01 00:00:00", 0, 0);
    assertTimestamp(
        "9999-12-31 23:59:59.999999",
        6,
        LocalTemporal.MAXIMUM_TIMESTAMP_MICROSECONDS);
  }

  @Test
  void exposesPrimitiveGregorianYearsAtDomainEdges() {
    assertEquals(1, LocalTemporal.year(LocalTemporal.MINIMUM_EPOCH_DAY));
    assertEquals(9_999, LocalTemporal.year(LocalTemporal.MAXIMUM_EPOCH_DAY));
    assertEquals(0, LocalTemporal.year(LocalTemporal.MAXIMUM_EPOCH_DAY + 1));
  }

  @Test
  void rejectsNonCanonicalAndOutOfDomainFields() {
    assertFalse(LocalTemporal.parseDate("0000-01-01", 0, 10, value));
    assertFalse(LocalTemporal.parseDate("1900-02-29", 0, 10, value));
    assertFalse(LocalTemporal.parseDate("2000-02-30", 0, 10, value));
    assertFalse(LocalTemporal.parseDate("2024-2-29", 0, 9, value));
    assertFalse(LocalTemporal.parseTime("24:00:00", 0, 8, value));
    assertFalse(LocalTemporal.parseTime("23:59:60", 0, 8, value));
    assertFalse(LocalTemporal.parseTime("12:00:00.", 0, 9, value));
    assertFalse(LocalTemporal.parseTime("12:00:00.1234567", 0, 16, value));
    assertFalse(LocalTemporal.parseTimestamp(
        "1970-01-01T00:00:00", 0, 19, value));

    assertFalse(LocalTemporal.validDate(LocalTemporal.MINIMUM_EPOCH_DAY - 1));
    assertFalse(LocalTemporal.validDate(LocalTemporal.MAXIMUM_EPOCH_DAY + 1));
    assertFalse(LocalTemporal.validTime(1, 3));
    assertFalse(LocalTemporal.validTime(LocalTemporal.MICROSECONDS_PER_DAY, 6));
    assertFalse(LocalTemporal.validTimestamp(
        LocalTemporal.MINIMUM_TIMESTAMP_MICROSECONDS - 1, 6));
    assertFalse(LocalTemporal.validTimestamp(
        LocalTemporal.MAXIMUM_TIMESTAMP_MICROSECONDS + 1, 6));
  }

  @Test
  void parsesFormatsAndClassifiesZonedInstants() {
    String text = "1970-01-01 00:00:00.123+01:00";
    assertEquals(
        StatusCode.OK,
        LocalTemporal.parseTimestampWithOffsetStatus(text, 0, text.length(), value));
    assertEquals(-3_599_877_000L, value.value);
    assertEquals(3, value.precision);
    assertEquals(60, value.offsetMinutes);
    assertEquals(
        text.length(),
        LocalTemporal.formatTimestampWithOffset(
            value.value, value.precision, value.offsetMinutes, formatted, 0));
    assertFormatted(text);

    assertEquals(
        StatusCode.INVALID_DATETIME_FORMAT,
        LocalTemporal.parseTimestampWithOffsetStatus(
            "1970-01-01 00:00:00Z", 0, 20, value));
    assertEquals(
        StatusCode.DATETIME_FIELD_OVERFLOW,
        LocalTemporal.parseTimestampWithOffsetStatus(
            "1970-01-01 24:00:00+00:00", 0, 25, value));
    assertEquals(
        StatusCode.INVALID_TIME_ZONE_DISPLACEMENT,
        LocalTemporal.parseTimestampWithOffsetStatus(
            "1970-01-01 00:00:00+14:01", 0, 25, value));
    assertEquals(
        StatusCode.DATETIME_FIELD_OVERFLOW,
        LocalTemporal.parseTimestampWithOffsetStatus(
            "0001-01-01 00:00:00+14:00", 0, 25, value));
    assertEquals(-1_000_000L, LocalTemporal.truncateToPrecision(-1, 0));
  }

  @Test
  void extractsUtcFieldsAndChecksDateArithmetic() {
    assertTrue(LocalTemporal.parseDate("2024-02-29", 0, 10, value));
    long leapDay = value.value;
    assertExtract(leapDay, SqlTypeDescriptor.DATE, LocalTemporal.EXTRACT_YEAR, 2024);
    assertExtract(leapDay, SqlTypeDescriptor.DATE, LocalTemporal.EXTRACT_MONTH, 2);
    assertExtract(leapDay, SqlTypeDescriptor.DATE, LocalTemporal.EXTRACT_DAY, 29);

    String localText = "1969-12-31 23:59:59.123456";
    assertTrue(LocalTemporal.parseTimestamp(
        localText, 0, localText.length(), value));
    long local = value.value;
    int localDescriptor = SqlTypeDescriptor.timestamp(6);
    assertExtract(local, localDescriptor, LocalTemporal.EXTRACT_YEAR, 1969);
    assertExtract(local, localDescriptor, LocalTemporal.EXTRACT_HOUR, 23);
    assertExtract(local, localDescriptor, LocalTemporal.EXTRACT_SECOND, 59_123_456);

    assertTrue(LocalTemporal.parseTime("12:34:56.123", 0, 12, value));
    assertExtract(
        value.value, SqlTypeDescriptor.time(3), LocalTemporal.EXTRACT_SECOND, 56_123);

    String instantText = "2024-01-01 01:02:03.004+01:00";
    assertEquals(
        StatusCode.OK,
        LocalTemporal.parseTimestampWithOffsetStatus(
            instantText, 0, instantText.length(), value));
    long instant = value.value;
    int instantDescriptor = SqlTypeDescriptor.timestampWithTimeZone(3);
    assertExtract(instant, instantDescriptor, LocalTemporal.EXTRACT_HOUR, 0);
    assertExtract(instant, instantDescriptor, LocalTemporal.EXTRACT_MINUTE, 2);
    assertExtract(instant, instantDescriptor, LocalTemporal.EXTRACT_SECOND, 3_004);
    assertExtract(instant, instantDescriptor, LocalTemporal.EXTRACT_TIMEZONE_HOUR, 0);
    assertExtract(instant, instantDescriptor, LocalTemporal.EXTRACT_TIMEZONE_MINUTE, 0);

    assertEquals(
        StatusCode.OK,
        LocalTemporal.addDateDays(
            LocalTemporal.MINIMUM_EPOCH_DAY, 3_652_058, value));
    assertEquals(LocalTemporal.MAXIMUM_EPOCH_DAY, value.value);
    assertEquals(
        StatusCode.OK,
        LocalTemporal.subtractDates(
            LocalTemporal.MAXIMUM_EPOCH_DAY,
            LocalTemporal.MINIMUM_EPOCH_DAY,
            value));
    assertEquals(3_652_058, value.value);
    assertEquals(
        StatusCode.DATETIME_FIELD_OVERFLOW,
        LocalTemporal.addDateDays(LocalTemporal.MAXIMUM_EPOCH_DAY, 1, value));
    assertEquals(
        StatusCode.DATETIME_FIELD_OVERFLOW,
        LocalTemporal.addDateDays(0, Long.MAX_VALUE, value));
    assertEquals(
        StatusCode.DATETIME_FIELD_OVERFLOW,
        LocalTemporal.subtractDateDays(LocalTemporal.MINIMUM_EPOCH_DAY, 1, value));
    assertEquals(
        StatusCode.DATETIME_FIELD_OVERFLOW,
        LocalTemporal.subtractDateDays(0, Long.MIN_VALUE, value));
    assertEquals(
        StatusCode.DATATYPE_MISMATCH,
        LocalTemporal.extract(
            0, SqlTypeDescriptor.time(0), LocalTemporal.EXTRACT_YEAR, value));
  }

  private void assertDate(String text, long expected) {
    assertTrue(LocalTemporal.parseDate(text, 0, text.length(), value));
    assertEquals(expected, value.value);
    assertEquals(10, LocalTemporal.formatDate(value.value, formatted, 0));
    assertFormatted(text);
  }

  private void assertTime(String text, int precision, long expected) {
    assertTrue(LocalTemporal.parseTime(text, 0, text.length(), value));
    assertEquals(expected, value.value);
    assertEquals(precision, value.precision);
    assertEquals(text.length(), LocalTemporal.formatTime(
        value.value, precision, formatted, 0));
    assertFormatted(text);
  }

  private void assertTimestamp(String text, int precision, long expected) {
    assertTrue(LocalTemporal.parseTimestamp(text, 0, text.length(), value));
    assertEquals(expected, value.value);
    assertEquals(precision, value.precision);
    assertEquals(text.length(), LocalTemporal.formatTimestamp(
        value.value, precision, formatted, 0));
    assertFormatted(text);
  }

  private void assertExtract(long source, int descriptor, int field, long expected) {
    assertEquals(StatusCode.OK, LocalTemporal.extract(source, descriptor, field, value));
    assertEquals(expected, value.value);
  }

  private void assertFormatted(String expected) {
    for (int index = 0; index < expected.length(); index++) {
      assertEquals(expected.charAt(index), formatted[index]);
    }
  }
}
