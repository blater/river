package io.riverdb.sql;

import java.util.Arrays;

/** Atomic geometric storage growth for projection programs and symbols. */
final class SqlProjectionCapacity {
  private SqlProjectionCapacity() { }

  static void initialize(
      SqlScalarExpression[] expressions,
      SqlIdentifier[] tables,
      SqlIdentifier[] names,
      int first) {
    for (int index = first; index < expressions.length; index++) {
      expressions[index] = new SqlScalarExpression();
    }
    for (int index = first * 2; index < names.length; index++) {
      tables[index] = new SqlIdentifier();
      names[index] = new SqlIdentifier();
    }
  }

  static boolean ensureExpressions(SqlProjectionList list, int required) {
    if (required <= list.expressions.length) return true;
    int capacity = grow(list.expressions.length, required, SqlCommand.MAXIMUM_PROJECTIONS);
    try {
      SqlScalarExpression[] grown = Arrays.copyOf(list.expressions, capacity);
      for (int index = list.expressions.length; index < capacity; index++) {
        grown[index] = new SqlScalarExpression();
      }
      list.expressions = grown;
      return true;
    } catch (OutOfMemoryError error) {
      return false;
    }
  }

  static SqlScalarExpression expression(SqlProjectionList list, int index) {
    return index >= 0 && index < SqlCommand.MAXIMUM_PROJECTIONS
        && ensureExpressions(list, index + 1) ? list.expressions[index] : null;
  }

  static boolean ensureSymbols(SqlProjectionList list, int required) {
    if (required <= list.symbolNames.length) return true;
    int capacity = grow(list.symbolNames.length, required, SqlProjectionList.MAXIMUM_SYMBOLS);
    try {
      SqlIdentifier[] tables = Arrays.copyOf(list.symbolTables, capacity);
      SqlIdentifier[] names = Arrays.copyOf(list.symbolNames, capacity);
      for (int index = list.symbolNames.length; index < capacity; index++) {
        tables[index] = new SqlIdentifier();
        names[index] = new SqlIdentifier();
      }
      list.symbolTables = tables;
      list.symbolNames = names;
      return true;
    } catch (OutOfMemoryError error) {
      return false;
    }
  }

  static boolean ensureSymbolSlot(SqlProjectionList list, int count) {
    return count < SqlProjectionList.MAXIMUM_SYMBOLS
        && ensureSymbols(list, count + 1);
  }

  private static int grow(int current, int required, int maximum) {
    int capacity = current;
    while (capacity < required) capacity = Math.min(maximum, capacity * 2);
    return capacity;
  }
}
