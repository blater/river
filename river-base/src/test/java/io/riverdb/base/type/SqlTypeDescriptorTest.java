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

    int text = SqlTypeDescriptor.varchar(255);
    assertTrue(SqlTypeDescriptor.isValid(text));
    assertEquals(SqlTypeDescriptor.TYPE_ID_VARCHAR, SqlTypeDescriptor.typeId(text));
    assertEquals(255, SqlTypeDescriptor.parameterOne(text));
    assertEquals(0, SqlTypeDescriptor.parameterTwo(text));
    assertEquals(
        SqlTypeDescriptor.LENGTH_UNIT_UNICODE_SCALAR,
        SqlTypeDescriptor.lengthUnit(text));

    int decimal = SqlTypeDescriptor.decimal(18, 6);
    assertTrue(SqlTypeDescriptor.isValid(decimal));
    assertEquals(18, SqlTypeDescriptor.parameterOne(decimal));
    assertEquals(6, SqlTypeDescriptor.parameterTwo(decimal));
    assertEquals(
        SqlTypeDescriptor.PRECISION_UNIT_DECIMAL_DIGIT,
        SqlTypeDescriptor.precisionUnit(decimal));
  }

  @Test
  void rejectsUnknownReservedAndOutOfRangeDescriptors() {
    assertFalse(SqlTypeDescriptor.isValid(0));
    assertFalse(SqlTypeDescriptor.isValid(9));
    assertFalse(SqlTypeDescriptor.isValid(0x01000001));
    assertEquals(0, SqlTypeDescriptor.varchar(0));
    assertEquals(0, SqlTypeDescriptor.varchar(256));
    assertEquals(0, SqlTypeDescriptor.decimal(0, 0));
    assertEquals(0, SqlTypeDescriptor.decimal(18, 19));
    assertEquals(0, SqlTypeDescriptor.time(7));
    assertEquals(0, SqlTypeDescriptor.timestamp(-1));
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
    assertFalse(SqlTypeDescriptor.canImplicitlyCast(SqlTypeDescriptor.BIGINT, decimalNineTwo));
    assertTrue(SqlTypeDescriptor.canExplicitlyCast(SqlTypeDescriptor.BIGINT, decimalNineTwo));
    assertTrue(SqlTypeDescriptor.canExplicitlyCast(SqlTypeDescriptor.DATE, timestamp));
    assertTrue(SqlTypeDescriptor.canExplicitlyCast(timestamp, instant));
    assertFalse(SqlTypeDescriptor.canExplicitlyCast(SqlTypeDescriptor.BOOLEAN, timestamp));
  }
}
