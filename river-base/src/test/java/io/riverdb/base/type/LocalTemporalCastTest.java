package io.riverdb.base.type;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import org.junit.jupiter.api.Test;

final class LocalTemporalCastTest {
  private final LocalTemporal.Value value = new LocalTemporal.Value();
  private final LocalTemporalCast.TextResult text = new LocalTemporalCast.TextResult();
  private final char[] characters = new char[LocalTemporalCast.MAXIMUM_TEXT_CHARACTERS];

  @Test
  void castsTemporalPrecisionAndDateTimestampExactly() {
    assertEquals(
        StatusCode.OK,
        LocalTemporalCast.castFixed(
            12_345_000, SqlTypeDescriptor.time(6), SqlTypeDescriptor.time(3), value));
    assertEquals(12_345_000, value.value);
    assertEquals(
        StatusCode.DATETIME_FIELD_OVERFLOW,
        LocalTemporalCast.castFixed(
            12_345_001, SqlTypeDescriptor.time(6), SqlTypeDescriptor.time(3), value));
    assertEquals(
        StatusCode.DATETIME_FIELD_OVERFLOW,
        LocalTemporalCast.castFixed(
            12_345_001, SqlTypeDescriptor.time(3), SqlTypeDescriptor.time(6), value));

    assertEquals(
        StatusCode.OK,
        LocalTemporalCast.castFixed(
            -1, SqlTypeDescriptor.DATE, SqlTypeDescriptor.timestamp(6), value));
    assertEquals(-LocalTemporal.MICROSECONDS_PER_DAY, value.value);
    assertEquals(
        StatusCode.OK,
        LocalTemporalCast.castFixed(
            value.value, SqlTypeDescriptor.timestamp(6), SqlTypeDescriptor.DATE, value));
    assertEquals(-1, value.value);
    assertEquals(
        StatusCode.DATETIME_FIELD_OVERFLOW,
        LocalTemporalCast.castFixed(
            -1, SqlTypeDescriptor.timestamp(6), SqlTypeDescriptor.DATE, value));
    assertEquals(
        StatusCode.FEATURE_NOT_SUPPORTED,
        LocalTemporalCast.castFixed(
            0,
            SqlTypeDescriptor.timestamp(6),
            SqlTypeDescriptor.timestampWithTimeZone(6),
            value));
  }

  @Test
  void parsesStrictCanonicalTextWithTargetPrecision() {
    assertEquals(
        StatusCode.OK,
        LocalTemporalCast.parseText(
            "23:59:59.999", 0, 12, SqlTypeDescriptor.time(6), value));
    assertEquals(86_399_999_000L, value.value);
    assertEquals(6, value.precision);
    assertEquals(
        StatusCode.DATETIME_FIELD_OVERFLOW,
        LocalTemporalCast.parseText(
            "23:59:59.999001", 0, 15, SqlTypeDescriptor.time(3), value));
    assertEquals(
        StatusCode.INVALID_DATETIME_FORMAT,
        LocalTemporalCast.parseText(
            "2024-02-29x", 0, 11, SqlTypeDescriptor.DATE, value));
    assertEquals(
        StatusCode.DATETIME_FIELD_OVERFLOW,
        LocalTemporalCast.parseText(
            "2023-02-29", 0, 10, SqlTypeDescriptor.DATE, value));
    assertEquals(
        StatusCode.INVALID_TIME_ZONE_DISPLACEMENT,
        LocalTemporalCast.parseText(
            "1970-01-01 00:00:00+14:01",
            0,
            25,
            SqlTypeDescriptor.timestampWithTimeZone(0),
            value));
  }

  @Test
  void formatsCanonicalTextWithoutTruncation() {
    assertEquals(
        StatusCode.OK,
        LocalTemporalCast.formatText(
            -1,
            SqlTypeDescriptor.timestampWithTimeZone(6),
            SqlTypeDescriptor.varchar(32),
            characters,
            0,
            text));
    assertEquals("1969-12-31 23:59:59.999999+00:00", new String(characters, 0, text.length));
    assertEquals(
        StatusCode.STRING_DATA_RIGHT_TRUNCATION,
        LocalTemporalCast.formatText(
            -1,
            SqlTypeDescriptor.timestampWithTimeZone(6),
            SqlTypeDescriptor.varchar(31),
            characters,
            0,
            text));
    assertEquals(0, text.length);
    assertEquals(32, LocalTemporalCast.canonicalLength(
        SqlTypeDescriptor.timestampWithTimeZone(6)));
  }
}
