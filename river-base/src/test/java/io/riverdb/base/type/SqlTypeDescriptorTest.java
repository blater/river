package io.riverdb.base.type;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class SqlTypeDescriptorTest {
  @Test
  void preservesStableIdsAndBoundedParameters() {
    assertEquals(1, SqlTypeDescriptor.TYPE_ID_BIGINT);
    assertEquals(2, SqlTypeDescriptor.TYPE_ID_VARCHAR);
    assertEquals(3, SqlTypeDescriptor.TYPE_ID_BOOLEAN);
    assertEquals(4, SqlTypeDescriptor.TYPE_ID_DECIMAL);
    assertEquals(5, SqlTypeDescriptor.TYPE_ID_DATE);
    assertEquals(6, SqlTypeDescriptor.TYPE_ID_TIME);
    assertEquals(7, SqlTypeDescriptor.TYPE_ID_TIMESTAMP);
    assertEquals(8, SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE);
    assertEquals(9, SqlTypeDescriptor.TYPE_ID_SMALLINT);
    assertEquals(10, SqlTypeDescriptor.TYPE_ID_INTEGER);
    assertEquals(11, SqlTypeDescriptor.TYPE_ID_REAL);
    assertEquals(12, SqlTypeDescriptor.TYPE_ID_DOUBLE);
    assertTrue(SqlTypeDescriptor.isValid(SqlTypeDescriptor.SMALLINT));
    assertTrue(SqlTypeDescriptor.isValid(SqlTypeDescriptor.INTEGER));
    assertTrue(SqlTypeDescriptor.isValid(SqlTypeDescriptor.REAL));
    assertTrue(SqlTypeDescriptor.isValid(SqlTypeDescriptor.DOUBLE));

    int text = SqlTypeDescriptor.varchar(255);
    assertTrue(SqlTypeDescriptor.isValid(text));
    assertEquals(SqlTypeDescriptor.TYPE_ID_VARCHAR, SqlTypeDescriptor.typeId(text));
    assertEquals(255, SqlTypeDescriptor.parameterOne(text));
    assertEquals(0, SqlTypeDescriptor.parameterTwo(text));
    assertEquals(
        SqlTypeDescriptor.LENGTH_UNIT_UNICODE_SCALAR,
        SqlTypeDescriptor.lengthUnit(text));

    int wideText = SqlTypeDescriptor.varchar(65_535);
    assertTrue(SqlTypeDescriptor.isValid(wideText));
    assertEquals(65_535, SqlTypeDescriptor.parameterOne(wideText));
    assertEquals(0, SqlTypeDescriptor.parameterTwo(wideText));
    assertTrue(SqlTypeDescriptor.isValid(SqlTypeDescriptor.varchar(500)));

    int decimal = SqlTypeDescriptor.decimal(18, 6);
    assertTrue(SqlTypeDescriptor.isValid(decimal));
    assertEquals(18, SqlTypeDescriptor.parameterOne(decimal));
    assertEquals(6, SqlTypeDescriptor.parameterTwo(decimal));
    assertEquals(
        SqlTypeDescriptor.PRECISION_UNIT_DECIMAL_DIGIT,
        SqlTypeDescriptor.precisionUnit(decimal));
    int wideDecimal = SqlTypeDescriptor.decimal(38, 20);
    assertTrue(SqlTypeDescriptor.isValid(wideDecimal));
    assertTrue(SqlTypeDescriptor.isWideDecimal(wideDecimal));
    assertFalse(SqlTypeDescriptor.isWideDecimal(decimal));

    assertTrue(SqlTypeDescriptor.isValid(SqlTypeDescriptor.DATE));
    assertTrue(SqlTypeDescriptor.isValid(SqlTypeDescriptor.time(0)));
    assertTrue(SqlTypeDescriptor.isValid(SqlTypeDescriptor.time(6)));
    assertTrue(SqlTypeDescriptor.isValid(SqlTypeDescriptor.timestamp(3)));
    assertEquals(
        SqlTypeDescriptor.PRECISION_UNIT_FRACTIONAL_SECOND_DIGIT,
        SqlTypeDescriptor.precisionUnit(SqlTypeDescriptor.timestamp(3)));
  }

  @Test
  void rejectsUnknownReservedAndOutOfRangeDescriptors() {
    assertFalse(SqlTypeDescriptor.isValid(0));
    assertFalse(SqlTypeDescriptor.isValid(13));
    assertFalse(SqlTypeDescriptor.isValid(0x01000001));
    assertEquals(0, SqlTypeDescriptor.varchar(0));
    assertEquals(0, SqlTypeDescriptor.varchar(65_536));
    assertFalse(SqlTypeDescriptor.isValid(SqlTypeDescriptor.TYPE_ID_VARCHAR | (1 << 24)));
    assertEquals(0, SqlTypeDescriptor.decimal(0, 0));
    assertEquals(0, SqlTypeDescriptor.decimal(18, 19));
    assertEquals(0, SqlTypeDescriptor.decimal(39, 0));
    assertEquals(0, SqlTypeDescriptor.time(7));
    assertEquals(0, SqlTypeDescriptor.timestamp(-1));
  }

  @Test
  void admitsExactlyTheRepresentableDeclarations() {
    for (int parameters = 0; parameters <= 0xffff; parameters++) {
      for (int type = 1; type <= 12; type++) {
        int descriptor = type | parameters << 8;
        int canonical = switch (type) {
          case SqlTypeDescriptor.TYPE_ID_VARCHAR -> SqlTypeDescriptor.varchar(parameters);
          case SqlTypeDescriptor.TYPE_ID_DECIMAL ->
              SqlTypeDescriptor.decimal(parameters & 0xff, parameters >>> 8);
          case SqlTypeDescriptor.TYPE_ID_TIME -> SqlTypeDescriptor.time(parameters);
          case SqlTypeDescriptor.TYPE_ID_TIMESTAMP -> SqlTypeDescriptor.timestamp(parameters);
          case SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE ->
              SqlTypeDescriptor.timestampWithTimeZone(parameters);
          default -> type;
        };
        assertEquals(canonical == descriptor, SqlTypeDescriptor.isValid(descriptor));
      }
    }
    for (int bit = 24; bit < Integer.SIZE; bit++) {
      assertFalse(SqlTypeDescriptor.isValid(SqlTypeDescriptor.varchar(65_535) | 1 << bit));
      assertFalse(SqlTypeDescriptor.isValid(SqlTypeDescriptor.decimal(38, 38) | 1 << bit));
    }
  }

  @Test
  void comparisonAndCastAdmissionStillRejectMalformedDescriptors() {
    int[] malformed = {0, 255, SqlTypeDescriptor.INTEGER | 1 << 8,
        SqlTypeDescriptor.TYPE_ID_DECIMAL | 39 << 8,
        SqlTypeDescriptor.TYPE_ID_DECIMAL | 2 << 8 | 3 << 16,
        SqlTypeDescriptor.TYPE_ID_TIME | 1 << 16,
        SqlTypeDescriptor.varchar(10) | 1 << 24};
    for (int descriptor : malformed) {
      assertEquals(SqlTypeDescriptor.COMPARISON_NONE,
          SqlTypeDescriptor.comparisonFamily(descriptor));
      assertFalse(SqlTypeDescriptor.canCompare(descriptor, descriptor));
      assertFalse(SqlTypeDescriptor.canCompare(SqlTypeDescriptor.INTEGER, descriptor));
      assertFalse(SqlTypeDescriptor.canImplicitlyCast(descriptor, descriptor));
      assertFalse(SqlTypeDescriptor.canImplicitlyCast(SqlTypeDescriptor.INTEGER, descriptor));
      assertFalse(SqlTypeDescriptor.canExplicitlyCast(descriptor, descriptor));
      assertFalse(SqlTypeDescriptor.canExplicitlyCast(SqlTypeDescriptor.varchar(10), descriptor));
      assertFalse(SqlNumericTypeRules.isIntegral(descriptor));
      assertFalse(SqlNumericTypeRules.isExact(descriptor));
      assertFalse(SqlNumericTypeRules.isApproximate(descriptor));
      assertFalse(SqlNumericTypeRules.isNumeric(descriptor));
      assertFalse(SqlNumericTypeRules.canImplicitlyCast(descriptor, descriptor));
      assertFalse(SqlNumericTypeRules.canAssign(descriptor, descriptor));
    }
  }

  @Test
  void freezesComparisonAndCastFamiliesWithoutLossyImplicitConversion() {
    int decimalNineTwo = SqlTypeDescriptor.decimal(9, 2);
    int decimalTwelveFour = SqlTypeDescriptor.decimal(12, 4);
    int decimalNineOne = SqlTypeDescriptor.decimal(9, 1);
    int timestamp = SqlTypeDescriptor.timestamp(6);
    int instant = SqlTypeDescriptor.timestampWithTimeZone(6);
    int varcharSeven = SqlTypeDescriptor.varchar(7);
    int varcharSixtyFour = SqlTypeDescriptor.varchar(64);

    assertTrue(SqlTypeDescriptor.canCompare(SqlTypeDescriptor.BIGINT, decimalNineTwo));
    assertTrue(SqlTypeDescriptor.canCompare(SqlTypeDescriptor.SMALLINT, SqlTypeDescriptor.DOUBLE));
    assertTrue(SqlTypeDescriptor.canCompare(varcharSeven, varcharSixtyFour));
    assertFalse(SqlTypeDescriptor.canCompare(SqlTypeDescriptor.DATE, timestamp));
    assertTrue(SqlTypeDescriptor.canCompare(
        SqlTypeDescriptor.timestamp(3), timestamp));
    assertFalse(SqlTypeDescriptor.canCompare(
        SqlTypeDescriptor.time(3), timestamp));
    assertTrue(SqlTypeDescriptor.canImplicitlyCast(varcharSeven, varcharSixtyFour));
    assertFalse(SqlTypeDescriptor.canImplicitlyCast(varcharSixtyFour, varcharSeven));
    assertTrue(SqlTypeDescriptor.canImplicitlyCast(
        SqlTypeDescriptor.timestamp(3), timestamp));
    assertTrue(SqlTypeDescriptor.canExplicitlyCast(
        timestamp, SqlTypeDescriptor.timestamp(3)));
    assertTrue(SqlTypeDescriptor.canImplicitlyCast(decimalNineTwo, decimalTwelveFour));
    assertFalse(SqlTypeDescriptor.canImplicitlyCast(decimalNineTwo, decimalNineOne));
    assertTrue(SqlTypeDescriptor.canImplicitlyCast(SqlTypeDescriptor.INTEGER, decimalNineTwo));
    assertTrue(SqlTypeDescriptor.canImplicitlyCast(
        SqlTypeDescriptor.SMALLINT, SqlTypeDescriptor.INTEGER));
    assertFalse(SqlTypeDescriptor.canImplicitlyCast(
        SqlTypeDescriptor.INTEGER, SqlTypeDescriptor.SMALLINT));
    assertTrue(SqlTypeDescriptor.canImplicitlyCast(
        SqlTypeDescriptor.REAL, SqlTypeDescriptor.DOUBLE));
    assertFalse(SqlTypeDescriptor.canImplicitlyCast(
        SqlTypeDescriptor.DOUBLE, SqlTypeDescriptor.REAL));
    assertTrue(SqlTypeDescriptor.canExplicitlyCast(SqlTypeDescriptor.BIGINT, decimalNineTwo));
    assertTrue(SqlTypeDescriptor.canExplicitlyCast(SqlTypeDescriptor.DATE, timestamp));
    assertTrue(SqlTypeDescriptor.canExplicitlyCast(timestamp, instant));
    assertFalse(SqlTypeDescriptor.canExplicitlyCast(SqlTypeDescriptor.BOOLEAN, timestamp));
  }
}
