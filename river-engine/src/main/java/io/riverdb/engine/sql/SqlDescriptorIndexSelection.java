package io.riverdb.engine.sql;

import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.sql.SqlComparison;

/** Chooses a leading equality/range key and compatible uniform scan direction. */
final class SqlDescriptorIndexSelection {
  private SqlDescriptorIndexSelection() { }

  static void choose(
      TableDescriptor table, SqlDescriptorPredicateBindings bindings,
      int orderCount, int[] orderColumns, boolean[] descending,
      SqlDescriptorIndexChoice result) {
    choose(table, (SqlDescriptorIndexCandidateSource) bindings,
        orderCount, orderColumns, descending, result);
  }

  static void choose(
      TableDescriptor table, SqlDescriptorIndexCandidateSource candidates,
      int orderCount, int[] orderColumns, boolean[] descending,
      SqlDescriptorIndexChoice result) {
    result.reset();
    consider(table.primaryKey(), candidates, orderCount, orderColumns, descending, result);
    for (int index = 0; index < table.secondaryKeyCount(); index++) {
      consider(table.secondaryKeyAt(index), candidates,
          orderCount, orderColumns, descending, result);
    }
  }

  private static void consider(
      KeyDescriptor key, SqlDescriptorIndexCandidateSource candidates,
      int orderCount, int[] orderColumns, boolean[] descending,
      SqlDescriptorIndexChoice result) {
    if (key == null || key.keyId() <= 0) return;
    int equal = 0;
    while (equal < key.partCount()
        && candidates.find(key.columnOrdinalAt(equal), SqlComparison.EQUAL) >= 0) equal++;
    int lower = -1;
    int upper = -1;
    if (equal < key.partCount()) {
      int column = key.columnOrdinalAt(equal);
      lower = findLower(candidates, column);
      upper = findUpper(candidates, column);
    }
    boolean ordered = covers(key, equal, orderCount, orderColumns, descending);
    if (equal == 0 && lower < 0 && upper < 0 && !ordered) return;
    int score = (ordered ? 10_000 : 0) + equal * 10 + (lower >= 0 || upper >= 0 ? 1 : 0);
    if (score > result.score) result.set(
        key, equal, lower, upper,
        ordered && orderCount > 0 && descending[0] ? -1 : 1, ordered, score);
  }

  private static boolean covers(
      KeyDescriptor key, int equal, int count, int[] columns, boolean[] descending) {
    if (count <= 0 || count > key.partCount() - equal) return false;
    boolean direction = descending[0];
    for (int index = 0; index < count; index++) {
      if (descending[index] != direction
          || columns[index] != key.columnOrdinalAt(equal + index)) return false;
    }
    return true;
  }

  static int find(
      SqlDescriptorPredicateBindings bindings, int column, SqlComparison comparison) {
    return bindings.find(column, comparison);
  }

  private static int findLower(SqlDescriptorIndexCandidateSource candidates, int column) {
    int leaf = candidates.find(column, SqlComparison.GREATER_THAN);
    return leaf >= 0 ? leaf : candidates.find(column, SqlComparison.GREATER_OR_EQUAL);
  }

  private static int findUpper(SqlDescriptorIndexCandidateSource candidates, int column) {
    int leaf = candidates.find(column, SqlComparison.LESS_THAN);
    return leaf >= 0 ? leaf : candidates.find(column, SqlComparison.LESS_OR_EQUAL);
  }
}
