package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlParser;
import io.riverdb.sql.SqlQuery;
import org.junit.jupiter.api.Test;

final class SqlStoredViewZonePolicyCapacityTest {
  @Test
  void validatesAggregateOperandsBeyondPhysicalTableWidth() {
    int aggregates = SqlShapeLimits.MAX_TABLE_COLUMNS + 1;
    SqlCommand command = new SqlCommand();
    assertEquals(StatusCode.OK, new SqlParser().parse(query(aggregates), command));
    assertEquals(aggregates, command.aggregateInvocationCount());

    assertEquals(
        StatusCode.INVALID_TIME_ZONE_DISPLACEMENT,
        SqlStoredViewZonePolicy.validate(
            command, new SqlQuery(), new SqlTemporalZoneNames()));
  }

  private static String query(int aggregates) {
    StringBuilder sql = new StringBuilder(aggregates * 24);
    sql.append("SELECT ");
    for (int index = 0; index < aggregates; index++) {
      if (index != 0) sql.append(',');
      sql.append("MAX(c").append(index);
      if (index == aggregates - 1) {
        sql.append(" AT TIME ZONE 'No/Such'");
      }
      sql.append(") AS a").append(index);
    }
    return sql.append(" FROM t").toString();
  }
}
