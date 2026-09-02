package io.riverdb.base.type;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class SqlValueDomainTest {
  @Test
  void validatesEveryFixedWidthDescriptorAtItsRawBoundary() {
    assertTrue(SqlValueDomain.validFixed(SqlTypeDescriptor.BIGINT, Long.MIN_VALUE));
    assertTrue(SqlValueDomain.validFixed(SqlTypeDescriptor.SMALLINT, Short.MIN_VALUE));
    assertFalse(SqlValueDomain.validFixed(
        SqlTypeDescriptor.SMALLINT, (long) Short.MAX_VALUE + 1));
    assertTrue(SqlValueDomain.validFixed(SqlTypeDescriptor.INTEGER, Integer.MIN_VALUE));
    assertFalse(SqlValueDomain.validFixed(
        SqlTypeDescriptor.INTEGER, (long) Integer.MAX_VALUE + 1));
    assertTrue(SqlValueDomain.validFixed(
        SqlTypeDescriptor.REAL, SqlApproximateNumeric.realBits(1.25f)));
    assertTrue(SqlValueDomain.validFixed(
        SqlTypeDescriptor.DOUBLE, SqlApproximateNumeric.doubleBits(-42.5d)));
    assertFalse(SqlValueDomain.validFixed(
        SqlTypeDescriptor.REAL, Integer.toUnsignedLong(Float.floatToRawIntBits(-0.0f))));
    assertFalse(SqlValueDomain.validFixed(
        SqlTypeDescriptor.DOUBLE, Double.doubleToRawLongBits(Double.POSITIVE_INFINITY)));
    assertTrue(SqlValueDomain.validFixed(SqlTypeDescriptor.BOOLEAN, 1));
    assertFalse(SqlValueDomain.validFixed(SqlTypeDescriptor.BOOLEAN, 2));

    int decimal = SqlTypeDescriptor.decimal(18, 6);
    assertTrue(SqlValueDomain.validFixed(decimal, 999_999_999_999_999_999L));
    assertFalse(SqlValueDomain.validFixed(SqlTypeDescriptor.decimal(2, 0), 100));
    int wideDecimal = SqlTypeDescriptor.decimal(38, 0);
    assertFalse(SqlValueDomain.validFixed(wideDecimal, 1));
    assertTrue(SqlValueDomain.validDecimal128(
        wideDecimal, 542_101_086_242_752_217L, 68_739_955_140_067_328L));
    assertFalse(SqlValueDomain.validDecimal128(
        wideDecimal, 5_421_010_862_427_522_170L, 687_399_551_400_673_280L));

    assertTrue(SqlValueDomain.validFixed(
        SqlTypeDescriptor.DATE, LocalTemporal.MINIMUM_EPOCH_DAY));
    assertFalse(SqlValueDomain.validFixed(
        SqlTypeDescriptor.DATE, LocalTemporal.MAXIMUM_EPOCH_DAY + 1));
    assertTrue(SqlValueDomain.validFixed(SqlTypeDescriptor.time(3), 1_000));
    assertFalse(SqlValueDomain.validFixed(SqlTypeDescriptor.time(3), 1));
    assertTrue(SqlValueDomain.validFixed(
        SqlTypeDescriptor.timestamp(6),
        LocalTemporal.MAXIMUM_TIMESTAMP_MICROSECONDS));
    assertTrue(SqlValueDomain.validFixed(
        SqlTypeDescriptor.timestampWithTimeZone(0), 0));
    assertFalse(SqlValueDomain.validFixed(
        SqlTypeDescriptor.varchar(8), 0));
    assertFalse(SqlValueDomain.validFixed(0, 0));
  }
}
