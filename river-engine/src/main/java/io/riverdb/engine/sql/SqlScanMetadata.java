package io.riverdb.engine.sql;

/** Validates cursor ownership before exposing retained scan result metadata. */
final class SqlScanMetadata {
  private SqlScanMetadata() {}

  static CharSequence name(
      SqlScanCursor cursor, SqlQueryExecution owner, long generation,
      SqlPhysicalPlan plan, int index) {
    if (!valid(cursor, owner, generation, plan, index)) return null;
    return plan.resultName(index);
  }

  static int descriptor(
      SqlScanCursor cursor, SqlQueryExecution owner, long generation,
      SqlPhysicalPlan plan, int index) {
    if (!valid(cursor, owner, generation, plan, index)) return 0;
    return plan.resultType(index);
  }

  static boolean nullable(
      SqlScanCursor cursor, SqlQueryExecution owner, long generation,
      SqlPhysicalPlan plan, int index) {
    return valid(cursor, owner, generation, plan, index) && plan.resultNullable(index);
  }

  private static boolean valid(
      SqlScanCursor cursor, SqlQueryExecution owner, long generation,
      SqlPhysicalPlan plan, int index) {
    return cursor != null && cursor.isOwnedBy(owner, generation)
        && index >= 0 && index < plan.resultColumnCount();
  }
}
