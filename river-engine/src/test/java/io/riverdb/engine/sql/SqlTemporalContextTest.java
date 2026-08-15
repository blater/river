package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.LocalTemporalCast;
import io.riverdb.base.type.SqlDefaultKind;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlParser;
import io.riverdb.sql.SqlScalarExpression;
import org.junit.jupiter.api.Test;

final class SqlTemporalContextTest {
  @Test
  void reusesCapturedPrimitiveDefaults() {
    SqlTemporalContext temporal = new SqlTemporalContext();
    SqlParser parser = new SqlParser();
    SqlCommand command = new SqlCommand();
    SqlTemporalContext.LongResult value = new SqlTemporalContext.LongResult();

    assertEquals(StatusCode.OK, temporal.beginStatement());
    assertEquals(StatusCode.OK, parser.parse("SELECT CURRENT_TIMESTAMP", command));
    assertEquals(StatusCode.OK, temporal.resolveScalar(command));
    long captured = command.scalarExpression().operand(0);
    for (int row = 0; row < 64; row++) {
      assertEquals(
          StatusCode.OK,
          temporal.defaultValue(
              SqlDefaultKind.CURRENT_TIMESTAMP,
              SqlTypeDescriptor.timestampWithTimeZone(3),
              value));
      assertEquals(Math.floorDiv(captured, 1_000) * 1_000, value.value);
    }
    assertEquals(
        StatusCode.OK,
        parser.parse("SELECT EXTRACT(SECOND FROM CURRENT_TIMESTAMP)", command));
    assertEquals(StatusCode.OK, temporal.resolveScalar(command));
    assertEquals(SqlScalarExpression.LITERAL, command.scalarExpression().operator(0));
    assertEquals(captured, command.scalarExpression().operand(0));
    temporal.finishStatement();
  }

  @Test
  void admitsOnlyProfileZonesAndAppliesExplicitDstRules() {
    SqlTemporalContext temporal = new SqlTemporalContext();
    SqlParser parser = new SqlParser();
    SqlCommand command = new SqlCommand();

    assertSet(parser, temporal, command, "UTC", StatusCode.OK);
    assertSet(parser, temporal, command, "+14:00", StatusCode.OK);
    assertSet(parser, temporal, command, "Europe/London", StatusCode.OK);
    assertSet(
        parser, temporal, command, "GMT+01:00",
        StatusCode.INVALID_TIME_ZONE_DISPLACEMENT);
    assertSet(parser, temporal, command, "CET", StatusCode.INVALID_TIME_ZONE_DISPLACEMENT);
    assertSet(parser, temporal, command, "+1", StatusCode.INVALID_TIME_ZONE_DISPLACEMENT);
    assertSet(parser, temporal, command, "+14:01", StatusCode.INVALID_TIME_ZONE_DISPLACEMENT);

    assertConversion(
        parser,
        temporal,
        command,
        "TIMESTAMP '2024-03-31 01:30:00' AT TIME ZONE 'Europe/London'",
        StatusCode.INVALID_TIME_ZONE_DISPLACEMENT);
    assertConversion(
        parser,
        temporal,
        command,
        "TIMESTAMP '2023-10-29 01:30:00' AT TIME ZONE 'Europe/London'",
        StatusCode.INVALID_TIME_ZONE_DISPLACEMENT);

    long summer = converted(
        parser,
        temporal,
        command,
        "TIMESTAMP '2023-10-29 01:30:00' AT TIME ZONE '+01:00'");
    long winter = converted(
        parser,
        temporal,
        command,
        "TIMESTAMP '2023-10-29 01:30:00' AT TIME ZONE '+00:00'");
    assertEquals(3_600_000_000L, winter - summer);
    long londonWinter = converted(
        parser,
        temporal,
        command,
        "TIMESTAMP '2024-01-15 12:00:00' AT TIME ZONE 'Europe/London'");
    long utcWinter = converted(
        parser,
        temporal,
        command,
        "TIMESTAMP '2024-01-15 12:00:00' AT TIME ZONE '+00:00'");
    assertEquals(utcWinter, londonWinter);
    long localAtTwo = converted(
        parser,
        temporal,
        command,
        "TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00:00' "
            + "AT TIME ZONE '+02:00'");
    assertEquals(2 * 3_600_000_000L, localAtTwo);
    long beforeSpring = converted(
        parser,
        temporal,
        command,
        "TIMESTAMP WITH TIME ZONE '2024-03-31 00:30:00+00:00' "
            + "AT TIME ZONE 'Europe/London'");
    long afterSpring = converted(
        parser,
        temporal,
        command,
        "TIMESTAMP WITH TIME ZONE '2024-03-31 01:30:00+00:00' "
            + "AT TIME ZONE 'Europe/London'");
    assertEquals(2 * 3_600_000_000L, afterSpring - beforeSpring);
    assertFalse(SqlTemporalContext.timeZoneDatabaseVersion().isBlank());
  }

  @Test
  void keepsInstantTextUtcAndRequiresLosslessZoneCastPrecision() {
    SqlTemporalContext temporal = new SqlTemporalContext();
    SqlParser parser = new SqlParser();
    SqlCommand command = new SqlCommand();
    SqlTemporalContext.LongResult value = new SqlTemporalContext.LongResult();
    LocalTemporalCast.TextResult text = new LocalTemporalCast.TextResult();
    char[] characters = new char[32];

    assertSet(parser, temporal, command, "+02:00", StatusCode.OK);
    assertEquals(
        StatusCode.OK,
        temporal.formatTemporal(
            0,
            SqlTypeDescriptor.timestampWithTimeZone(0),
            SqlTypeDescriptor.varchar(32),
            characters,
            text));
    assertEquals("1970-01-01 00:00:00+00:00", new String(characters, 0, text.length));
    assertEquals(
        StatusCode.DATETIME_FIELD_OVERFLOW,
        temporal.castTemporal(
            123_456,
            SqlTypeDescriptor.timestamp(6),
            SqlTypeDescriptor.timestampWithTimeZone(3),
            value));
    assertEquals(
        StatusCode.DATETIME_FIELD_OVERFLOW,
        temporal.castTemporal(
            123_456,
            SqlTypeDescriptor.timestamp(3),
            SqlTypeDescriptor.timestampWithTimeZone(3),
            value));
    assertEquals(
        StatusCode.OK,
        temporal.castTemporal(
            123_000,
            SqlTypeDescriptor.timestamp(3),
            SqlTypeDescriptor.timestampWithTimeZone(3),
            value));
    assertEquals(-7_199_877_000L, value.value);
  }

  private static void assertSet(
      SqlParser parser,
      SqlTemporalContext temporal,
      SqlCommand command,
      String zone,
      StatusCode expected) {
    assertEquals(StatusCode.OK, parser.parse("SET TIME ZONE '" + zone + "'", command));
    assertEquals(expected, temporal.setTimeZone(command));
  }

  private static void assertConversion(
      SqlParser parser,
      SqlTemporalContext temporal,
      SqlCommand command,
      String expression,
      StatusCode expected) {
    assertEquals(StatusCode.OK, parser.parse("SELECT " + expression, command));
    assertEquals(StatusCode.OK, temporal.beginStatement());
    assertEquals(expected, temporal.resolveScalar(command));
    temporal.finishStatement();
  }

  private static long converted(
      SqlParser parser,
      SqlTemporalContext temporal,
      SqlCommand command,
      String expression) {
    assertConversion(parser, temporal, command, expression, StatusCode.OK);
    return command.scalarExpression().operand(0);
  }

}
