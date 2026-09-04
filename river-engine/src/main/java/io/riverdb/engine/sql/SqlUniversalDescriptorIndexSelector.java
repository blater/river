package io.riverdb.engine.sql;

import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.sql.SqlComparison;

/** Selects mandatory exact, leading-prefix, or one-part range join access. */
final class SqlUniversalDescriptorIndexSelector {
  private SqlUniversalDescriptorIndexSelector() { }

  static void select(
      TableDescriptor table, int role, SqlBoundJoinContext context,
      SqlBoundBooleanPredicateProgram where, SqlBlockColumnLineage lineage,
      int queryBlock, SqlUniversalDescriptorIndexChoice result) {
    result.reset();
    consider(table.primaryKey(), role, context, where, lineage, queryBlock, result);
    for (int index = 0; index < table.secondaryKeyCount(); index++) {
      consider(
          table.secondaryKeyAt(index), role, context, where, lineage, queryBlock, result);
    }
    if (result.key != null) bind(role, context, where, lineage, queryBlock, result);
  }

  private static void consider(
      KeyDescriptor key, int role, SqlBoundJoinContext context,
      SqlBoundBooleanPredicateProgram where, SqlBlockColumnLineage lineage,
      int queryBlock, SqlUniversalDescriptorIndexChoice result) {
    if (key == null || key.keyId() <= 0) return;
    int equal = 0;
    while (equal < key.partCount()
        && has(role, key.columnOrdinalAt(equal), SqlComparison.EQUAL,
            context, where, lineage, queryBlock)) equal++;
    boolean low = false;
    boolean high = false;
    if (equal < key.partCount()) {
      int column = key.columnOrdinalAt(equal);
      low = hasLower(role, column, context, where, lineage, queryBlock);
      high = hasUpper(role, column, context, where, lineage, queryBlock);
    }
    int score = equal * 10 + (low || high ? 1 : 0);
    if (score > result.score && score > 0) result.select(key, equal, low, high);
  }

  private static void bind(
      int role, SqlBoundJoinContext context, SqlBoundBooleanPredicateProgram where,
      SqlBlockColumnLineage lineage, int queryBlock,
      SqlUniversalDescriptorIndexChoice result) {
    for (int part = 0; part < result.equalParts; part++) find(
        role, result.key.columnOrdinalAt(part), SqlComparison.EQUAL,
        context, where, lineage, queryBlock, result.equal[part]);
    if (result.equalParts == result.key.partCount()) return;
    int column = result.key.columnOrdinalAt(result.equalParts);
    result.lowerComparison = findLower(
        role, column, context, where, lineage, queryBlock, result.lower);
    result.upperComparison = findUpper(
        role, column, context, where, lineage, queryBlock, result.upper);
  }

  private static boolean has(
      int role, int column, SqlComparison comparison, SqlBoundJoinContext context,
      SqlBoundBooleanPredicateProgram where, SqlBlockColumnLineage lineage,
      int queryBlock) {
    return find(role, column, comparison, context, where, lineage, queryBlock, null);
  }

  private static boolean find(
      int role, int column, SqlComparison comparison, SqlBoundJoinContext context,
      SqlBoundBooleanPredicateProgram where, SqlBlockColumnLineage lineage,
      int queryBlock, SqlUniversalDescriptorIndexBinding result) {
    SqlBoundBooleanPredicateProgram on = role == 0 ? null : context.onBoolean(role - 1);
    return SqlUniversalDescriptorIndexMatcher.find(
        on, context, queryBlock, role, column, comparison, null, result)
        || SqlUniversalDescriptorIndexMatcher.find(
            where, context, queryBlock, role, column, comparison, lineage, result);
  }

  private static boolean hasLower(
      int role, int column, SqlBoundJoinContext context,
      SqlBoundBooleanPredicateProgram where, SqlBlockColumnLineage lineage,
      int queryBlock) {
    return findLower(role, column, context, where, lineage, queryBlock, null) != null;
  }

  private static boolean hasUpper(
      int role, int column, SqlBoundJoinContext context,
      SqlBoundBooleanPredicateProgram where, SqlBlockColumnLineage lineage,
      int queryBlock) {
    return findUpper(role, column, context, where, lineage, queryBlock, null) != null;
  }

  private static SqlComparison findLower(
      int role, int column, SqlBoundJoinContext context,
      SqlBoundBooleanPredicateProgram where, SqlBlockColumnLineage lineage,
      int queryBlock, SqlUniversalDescriptorIndexBinding result) {
    if (find(role, column, SqlComparison.GREATER_THAN,
        context, where, lineage, queryBlock, result)) {
      return SqlComparison.GREATER_THAN;
    }
    return find(role, column, SqlComparison.GREATER_OR_EQUAL,
        context, where, lineage, queryBlock, result)
        ? SqlComparison.GREATER_OR_EQUAL : null;
  }

  private static SqlComparison findUpper(
      int role, int column, SqlBoundJoinContext context,
      SqlBoundBooleanPredicateProgram where, SqlBlockColumnLineage lineage,
      int queryBlock, SqlUniversalDescriptorIndexBinding result) {
    if (find(role, column, SqlComparison.LESS_THAN,
        context, where, lineage, queryBlock, result)) {
      return SqlComparison.LESS_THAN;
    }
    return find(role, column, SqlComparison.LESS_OR_EQUAL,
        context, where, lineage, queryBlock, result)
        ? SqlComparison.LESS_OR_EQUAL : null;
  }
}
