package io.riverdb.engine.sql;

import io.riverdb.sql.SqlCommandType;

/** Query kinds currently executable against catalog-v2 descriptor rows. */
final class SqlDescriptorQueryTypes {
  private SqlDescriptorQueryTypes() { }

  static boolean handles(SqlCommandType type) {
    return type == SqlCommandType.SCAN || type == SqlCommandType.SELECT
        || type == SqlCommandType.DISTINCT_SCAN || scalar(type) || grouped(type);
  }

  static boolean handlesUniversalJoin(SqlCommandType type) {
    return type == SqlCommandType.JOIN_SCAN;
  }

  static boolean grouped(SqlCommandType type) {
    return type == SqlCommandType.GROUP_COUNT
        || type == SqlCommandType.GROUP_COUNT_VALUE
        || type == SqlCommandType.GROUP_COUNT_DISTINCT
        || type == SqlCommandType.GROUP_SUM
        || type == SqlCommandType.GROUP_AVG
        || type == SqlCommandType.GROUP_MIN
        || type == SqlCommandType.GROUP_MAX;
  }

  static boolean scalar(SqlCommandType type) {
    return type == SqlCommandType.COUNT || type == SqlCommandType.COUNT_VALUE
        || type == SqlCommandType.COUNT_DISTINCT
        || type == SqlCommandType.SUM || type == SqlCommandType.AVG
        || type == SqlCommandType.MIN || type == SqlCommandType.MAX;
  }
}
