package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.sql.SqlComparison;
import org.junit.jupiter.api.Test;

final class SqlDescriptorComparisonTest {
  @Test
  void operatorsAgreeAcrossPrimitiveAndTypedCallersAtLongExtremes() {
    SqlExpressionEvaluator expressions = new SqlExpressionEvaluator();
    SqlComparison[] operators = {
        SqlComparison.EQUAL, SqlComparison.NOT_EQUAL, SqlComparison.LESS_THAN,
        SqlComparison.LESS_OR_EQUAL, SqlComparison.GREATER_THAN,
        SqlComparison.GREATER_OR_EQUAL, SqlComparison.HALF_OPEN_RANGE,
        SqlComparison.IN, SqlComparison.NOT_IN};
    boolean[][] expected = {
        {false, true, true, true, false, false, false, false, false},
        {true, false, false, true, false, true, false, false, false},
        {false, true, false, false, true, true, false, false, false}};
    long[] left = {Long.MIN_VALUE, Long.MAX_VALUE, Long.MAX_VALUE};
    long[] right = {Long.MAX_VALUE, Long.MAX_VALUE, Long.MIN_VALUE};
    for (int pair = 0; pair < left.length; pair++) {
      for (int operator = 0; operator < operators.length; operator++) {
        SqlComparison comparison = operators[operator];
        assertEquals(expected[pair][operator], SqlDescriptorComparison.matches(
            Long.compare(left[pair], right[pair]), comparison));
        assertEquals(expected[pair][operator], expressions.matchesComparison(
            left[pair], comparison, right[pair]));
        assertEquals(expected[pair][operator], SqlTypedValueComparison.matches(
            left[pair], SqlTypeDescriptor.BIGINT,
            right[pair], SqlTypeDescriptor.BIGINT, comparison));
      }
    }
  }
}
