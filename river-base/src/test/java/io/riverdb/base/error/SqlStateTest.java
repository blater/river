package io.riverdb.base.error;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class SqlStateTest {
  @Test
  void freezesV1TypeAndConversionStates() {
    assertEquals("00000", SqlState.SUCCESS);
    assertEquals("22000", SqlState.DATA_EXCEPTION);
    assertEquals("22001", SqlState.STRING_DATA_RIGHT_TRUNCATION);
    assertEquals("22003", SqlState.NUMERIC_VALUE_OUT_OF_RANGE);
    assertEquals("22007", SqlState.INVALID_DATETIME_FORMAT);
    assertEquals("22008", SqlState.DATETIME_FIELD_OVERFLOW);
    assertEquals("22009", SqlState.INVALID_TIME_ZONE_DISPLACEMENT);
    assertEquals("22012", SqlState.DIVISION_BY_ZERO);
    assertEquals("22018", SqlState.INVALID_CHARACTER_VALUE_FOR_CAST);
    assertEquals("22023", SqlState.INVALID_PARAMETER_VALUE);
    assertEquals("42804", SqlState.DATATYPE_MISMATCH);
    assertEquals("42846", SqlState.CANNOT_COERCE);
  }
}
