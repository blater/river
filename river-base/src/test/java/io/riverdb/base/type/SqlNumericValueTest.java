package io.riverdb.base.type;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import org.junit.jupiter.api.Test;

final class SqlNumericValueTest {
  private final ExactDecimal.LongValue result = new ExactDecimal.LongValue();
  private final ExactDecimal.WideScratch scratch = new ExactDecimal.WideScratch();

  @Test
  void assignmentRoundsScaleAndChecksNarrowIntegralRanges() {
    int source = SqlTypeDescriptor.decimal(4, 3);
    int target = SqlTypeDescriptor.decimal(3, 2);
    assertEquals(StatusCode.OK,
        SqlNumericValue.assign(1_235, source, target, result, scratch));
    assertEquals(124, result.value);
    assertEquals(StatusCode.OK,
        SqlNumericValue.assign(-1_235, source, target, result, scratch));
    assertEquals(-124, result.value);
    assertEquals(StatusCode.NUMERIC_VALUE_OUT_OF_RANGE,
        SqlNumericValue.assign(
            (long) Short.MAX_VALUE + 1, SqlTypeDescriptor.BIGINT,
            SqlTypeDescriptor.SMALLINT, result, scratch));
  }

  @Test
  void approximateAssignmentRejectsNonFiniteAndRoundsTiesAwayFromZero() {
    assertEquals(StatusCode.OK, SqlNumericValue.assign(
        SqlApproximateNumeric.doubleBits(12.5d), SqlTypeDescriptor.DOUBLE,
        SqlTypeDescriptor.INTEGER, result, scratch));
    assertEquals(13, result.value);
    assertEquals(StatusCode.OK, SqlNumericValue.assign(
        SqlApproximateNumeric.doubleBits(-12.5d), SqlTypeDescriptor.DOUBLE,
        SqlTypeDescriptor.INTEGER, result, scratch));
    assertEquals(-13, result.value);
    assertEquals(StatusCode.NUMERIC_VALUE_OUT_OF_RANGE, SqlNumericValue.assign(
        SqlApproximateNumeric.doubleBits(1.0e20d), SqlTypeDescriptor.DOUBLE,
        SqlTypeDescriptor.decimal(18, 2), result, scratch));
  }
}
