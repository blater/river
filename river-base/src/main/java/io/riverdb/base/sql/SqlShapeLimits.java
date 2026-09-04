package io.riverdb.base.sql;

/** Compiled limits for independently bounded SQL shapes and encoded values. */
public final class SqlShapeLimits {
  public static final int MAX_TABLE_COLUMNS = 1_024;
  public static final int MAX_RESULT_COLUMNS = 1_664;
  public static final int MAX_SELECT_EXPRESSIONS = 1_664;
  public static final int MAX_RETURNING_EXPRESSIONS = 1_664;
  public static final int MAX_GROUP_BY_EXPRESSIONS = 1_664;
  public static final int MAX_ORDER_BY_EXPRESSIONS = 1_664;
  public static final int MAX_DISTINCT_EXPRESSIONS = 1_664;
  public static final int MAX_ROW_CONSTRUCTOR_EXPRESSIONS = 1_664;
  public static final int MAX_TUPLE_PARTS = 1_664;
  public static final int MAX_INSERT_COLUMNS = 1_024;
  public static final int MAX_UPDATE_ASSIGNMENTS = 1_024;

  public static final int MAX_JOIN_ROLES = 64;
  public static final int MAX_QUERY_BLOCKS = 32;
  public static final int MAX_ACTIVE_QUERY_SCANS = MAX_JOIN_ROLES * MAX_QUERY_BLOCKS;
  public static final int MAX_PLAN_STEPS = 256;
  public static final int MAX_KEY_PARTS = 32;
  public static final int MAX_SECONDARY_INDEXES = 64;
  public static final int MAX_TABLE_INDEXES = 1 + MAX_SECONDARY_INDEXES;
  public static final int MAX_FOREIGN_KEYS = 64;
  public static final int MAX_TABLE_KEY_PARTS =
      (1 + MAX_SECONDARY_INDEXES + MAX_FOREIGN_KEYS) * MAX_KEY_PARTS;
  public static final int MAX_CHECK_CONSTRAINTS = 1_024;

  public static final int MAX_AGGREGATES = 1_664;
  public static final int MAX_PREDICATE_LEAVES = 4_096;
  public static final int MAX_EXPRESSION_NODES = 16_384;
  public static final int MAX_EXPRESSION_DEPTH = 64;

  public static final int MAX_ENCODED_SCHEMA_BYTES = 1_024 * 1_024;
  public static final int MAX_SCHEMA_CHUNKS = 160;
  public static final int MAX_INDEX_USER_KEY_BYTES = 3_072;
  public static final int MAX_PHYSICAL_INDEX_KEY_BYTES = 3_080;
  public static final int MAX_ENCODED_RESULT_ROW_BYTES = 4 * 1_024 * 1_024;
  public static final int MAX_SQL_TEXT_BYTES = 1_024 * 1_024;
  public static final int MAX_STORED_VIEW_SQL_BYTES = 256 * 1_024;
  public static final int MAX_PARAMETERS = 65_535;
  public static final int MAX_ENCODED_PARAMETER_BYTES = 16 * 1_024 * 1_024;

  private SqlShapeLimits() {
  }
}
