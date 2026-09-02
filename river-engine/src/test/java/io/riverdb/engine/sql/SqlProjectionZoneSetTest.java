package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import org.junit.jupiter.api.Test;

final class SqlProjectionZoneSetTest {
  @Test
  void wideNonTemporalShapeRetainsNoPerProjectionZonePlans() {
    SqlProjectionZoneSet zones = new SqlProjectionZoneSet();

    assertEquals(StatusCode.OK, zones.reserve(SqlShapeLimits.MAX_RESULT_COLUMNS));
    assertEquals(0, zones.retainedPlanCount());
    assertSame(zones.get(0), zones.get(SqlShapeLimits.MAX_RESULT_COLUMNS - 1));

    assertEquals(StatusCode.OK, zones.ensure(SqlShapeLimits.MAX_RESULT_COLUMNS - 1));
    assertEquals(1, zones.retainedPlanCount());
  }
}
