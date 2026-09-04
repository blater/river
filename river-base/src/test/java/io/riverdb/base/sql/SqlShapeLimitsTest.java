package io.riverdb.base.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class SqlShapeLimitsTest {
  @Test
  void exposesIndependentCountLimits() {
    assertEquals(1_024, SqlShapeLimits.MAX_TABLE_COLUMNS);
    assertEquals(1_664, SqlShapeLimits.MAX_RESULT_COLUMNS);
    assertEquals(1_664, SqlShapeLimits.MAX_SELECT_EXPRESSIONS);
    assertEquals(1_664, SqlShapeLimits.MAX_RETURNING_EXPRESSIONS);
    assertEquals(1_664, SqlShapeLimits.MAX_GROUP_BY_EXPRESSIONS);
    assertEquals(1_664, SqlShapeLimits.MAX_ORDER_BY_EXPRESSIONS);
    assertEquals(1_664, SqlShapeLimits.MAX_DISTINCT_EXPRESSIONS);
    assertEquals(1_664, SqlShapeLimits.MAX_ROW_CONSTRUCTOR_EXPRESSIONS);
    assertEquals(1_664, SqlShapeLimits.MAX_TUPLE_PARTS);
    assertEquals(1_024, SqlShapeLimits.MAX_INSERT_COLUMNS);
    assertEquals(1_024, SqlShapeLimits.MAX_UPDATE_ASSIGNMENTS);
    assertEquals(64, SqlShapeLimits.MAX_JOIN_ROLES);
    assertEquals(256, SqlShapeLimits.MAX_PLAN_STEPS);
    assertEquals(32, SqlShapeLimits.MAX_KEY_PARTS);
    assertEquals(64, SqlShapeLimits.MAX_SECONDARY_INDEXES);
    assertEquals(64, SqlShapeLimits.MAX_FOREIGN_KEYS);
    assertEquals(4_128, SqlShapeLimits.MAX_TABLE_KEY_PARTS);
    assertEquals(1_024, SqlShapeLimits.MAX_CHECK_CONSTRAINTS);
    assertEquals(1_664, SqlShapeLimits.MAX_AGGREGATES);
    assertEquals(4_096, SqlShapeLimits.MAX_PREDICATE_LEAVES);
    assertEquals(16_384, SqlShapeLimits.MAX_EXPRESSION_NODES);
    assertEquals(64, SqlShapeLimits.MAX_EXPRESSION_DEPTH);
  }

  @Test
  void exposesIndependentByteLimits() {
    assertEquals(1_024 * 1_024, SqlShapeLimits.MAX_ENCODED_SCHEMA_BYTES);
    assertEquals(160, SqlShapeLimits.MAX_SCHEMA_CHUNKS);
    assertEquals(3_072, SqlShapeLimits.MAX_INDEX_USER_KEY_BYTES);
    assertEquals(3_080, SqlShapeLimits.MAX_PHYSICAL_INDEX_KEY_BYTES);
    assertEquals(
        SqlShapeLimits.MAX_INDEX_USER_KEY_BYTES + Long.BYTES,
        SqlShapeLimits.MAX_PHYSICAL_INDEX_KEY_BYTES);
    assertEquals(4 * 1_024 * 1_024, SqlShapeLimits.MAX_ENCODED_RESULT_ROW_BYTES);
    assertEquals(1_024 * 1_024, SqlShapeLimits.MAX_SQL_TEXT_BYTES);
    assertEquals(256 * 1_024, SqlShapeLimits.MAX_STORED_VIEW_SQL_BYTES);
    assertEquals(65_535, SqlShapeLimits.MAX_PARAMETERS);
    assertEquals(16 * 1_024 * 1_024, SqlShapeLimits.MAX_ENCODED_PARAMETER_BYTES);
  }
}
