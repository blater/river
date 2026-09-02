package io.riverdb.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import org.junit.jupiter.api.Test;

final class SqlMultiExpressionShapeTest {
  @Test
  void parsesVisibleAggregateSetAndHiddenRepeatedComputedGroups() {
    SqlParser parser = new SqlParser();
    SqlCommand command = new SqlCommand();
    assertEquals(StatusCode.OK, parser.parse(
        "SELECT a,b,COUNT(*),SUM(c),MAX(d) FROM t GROUP BY a,b "
            + "HAVING SUM(c)>1 ORDER BY a,b", command));
    assertEquals(3, command.aggregateOutputCount());
    assertEquals(3, command.aggregateInvocationCount());
    assertEquals(2, command.groupExpressionCount());
    assertEquals(0, command.groupProjection(0));
    assertEquals(1, command.groupProjection(1));
    assertEquals(StatusCode.OK, parser.parse(
        "SELECT COUNT(*),SUM(c) FROM t GROUP BY a+1,a+1 HAVING SUM(c)>1", command));
    assertEquals(2, command.aggregateOutputCount());
    assertEquals(2, command.groupExpressionCount());
    assertEquals(-1, command.groupProjection(0));
    assertEquals(-1, command.groupProjection(1));
    assertTrue(SqlAggregateExpressionParser.same(
        command, command.groupExpression(0), command.groupExpression(1)));
    assertEquals(StatusCode.OK, parser.parse(
        "SELECT a,b FROM t GROUP BY a,b HAVING b>1 ORDER BY a,b", command));
    assertEquals(SqlCommandType.DISTINCT_SCAN, command.type());
    assertEquals(2, command.groupExpressionCount());
    assertEquals(1, command.booleanHavingPredicates().programOperand(
        0, SqlBooleanPredicateProgram.PROGRAM_LEFT, 0));
    assertEquals(StatusCode.OK, parser.parse(
        "SELECT a,a FROM t GROUP BY a ORDER BY a", command));
    assertEquals(1, command.groupExpressionCount());
    assertEquals(StatusCode.OK, parser.parse(
        "SELECT COUNT(*) FROM t GROUP BY a,b HAVING b=2", command));
    assertEquals(1, command.booleanHavingPredicates().programOperand(
        0, SqlBooleanPredicateProgram.PROGRAM_LEFT, 0));
    assertEquals(StatusCode.OK, parser.parse(
        "SELECT b,a,COUNT(*) FROM t GROUP BY a,b HAVING a=1", command));
    assertEquals(0, command.booleanHavingPredicates().programOperand(
        0, SqlBooleanPredicateProgram.PROGRAM_LEFT, 0));
    assertEquals(StatusCode.OK, parser.parse(
        "SELECT a+1 AS shifted,a+1 AS repeated,COUNT(*) FROM t "
            + "GROUP BY a+1 HAVING repeated=2", command));
    assertEquals(0, command.booleanHavingPredicates().programOperand(
        0, SqlBooleanPredicateProgram.PROGRAM_LEFT, 0));
  }

  @Test
  void groupValueCarriesWideKeyOrdinal() {
    int[] ordinals = {8, 63, 255, 1_663};
    SqlParser parser = new SqlParser();
    SqlCommand command = new SqlCommand();
    for (int ordinal : ordinals) {
      assertEquals(StatusCode.OK, parser.parse(groupedHaving(ordinal), command));
      assertEquals(ordinal, command.booleanHavingPredicates().programOperand(
          0, SqlBooleanPredicateProgram.PROGRAM_LEFT, 0));
    }
  }

  @Test
  void distinctAndOrderListsCrossWordBoundaries() {
    int[] boundaries = {8, 9, 63, 64, 65, SqlShapeLimits.MAX_RESULT_COLUMNS};
    SqlParser parser = new SqlParser();
    SqlCommand command = new SqlCommand();
    for (int count : boundaries) {
      assertEquals(StatusCode.OK, parser.parse(select(count), command));
      assertEquals(count, command.columnCount());
      assertEquals(count, command.orderExpressionCount());
      assertTrue(command.isDescendingOrder(0));
      assertFalse(command.isDescendingOrder(count - 1));
    }
    assertEquals(StatusCode.RESOURCE_EXHAUSTED,
        parser.parse(select(SqlShapeLimits.MAX_RESULT_COLUMNS + 1), command));
  }

  private static String select(int count) {
    StringBuilder sql = new StringBuilder("SELECT DISTINCT ");
    appendColumns(sql, count);
    sql.append(" FROM wide_shape ORDER BY ");
    for (int column = 0; column < count; column++) {
      if (column > 0) sql.append(',');
      sql.append('c').append(column);
      if (column == 0) sql.append(" DESC");
    }
    return sql.toString();
  }

  private static String groupedHaving(int ordinal) {
    StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM wide_shape GROUP BY ");
    for (int group = 0; group <= ordinal; group++) {
      if (group > 0) sql.append(',');
      sql.append('g').append(group);
    }
    return sql.append(" HAVING g").append(ordinal).append("=1").toString();
  }

  private static void appendColumns(StringBuilder sql, int count) {
    for (int column = 0; column < count; column++) {
      if (column > 0) sql.append(',');
      sql.append('c').append(column);
    }
  }
}
