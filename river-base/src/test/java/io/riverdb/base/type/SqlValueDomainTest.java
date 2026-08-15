package io.riverdb.base.type;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class SqlValueDomainTest {
  @Test
  void validatesEveryFixedWidthDescriptorAtItsRawBoundary() {
    assertTrue(SqlValueDomain.validFixed(SqlTypeDescriptor.BIGINT, Long.MIN_VALUE));
    assertTrue(SqlValueDomain.validFixed(SqlTypeDescriptor.BOOLEAN, 1));
    assertFalse(SqlValueDomain.validFixed(SqlTypeDescriptor.BOOLEAN, 2));

    int decimal = SqlTypeDescriptor.decimal(18, 6);
    assertTrue(SqlValueDomain.validFixed(decimal, 999_999_999_999_999_999L));
    assertFalse(SqlValueDomain.validFixed(SqlTypeDescriptor.decimal(2, 0), 100));

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
