package io.riverdb.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.SqlState;
import io.riverdb.base.error.StatusCode;
import org.junit.jupiter.api.Test;

final class JdbcTemporalStatusTest {
  @Test
  void mapsTemporalFailuresToExactSqlStates() {
    assertState(StatusCode.INVALID_DATETIME_FORMAT, SqlState.INVALID_DATETIME_FORMAT);
    assertState(StatusCode.DATETIME_FIELD_OVERFLOW, SqlState.DATETIME_FIELD_OVERFLOW);
    assertState(
        StatusCode.INVALID_TIME_ZONE_DISPLACEMENT,
        SqlState.INVALID_TIME_ZONE_DISPLACEMENT);
    assertState(
        StatusCode.STRING_DATA_RIGHT_TRUNCATION,
        SqlState.STRING_DATA_RIGHT_TRUNCATION);
    assertState(
        StatusCode.FEATURE_NOT_SUPPORTED,
        SqlState.FEATURE_NOT_SUPPORTED);
  }

  private static void assertState(StatusCode status, String expected) {
    var failure = JdbcExceptions.failure(status, "temporal");
    assertEquals(expected, failure.getSQLState());
    assertEquals(status.stableCode(), failure.getErrorCode());
  }
}
