package io.riverdb.sql;

import io.riverdb.base.sql.SqlShapeLimits;

/** Stateless array operations shared by the retained table-constraint set. */
final class SqlTableConstraintArrays {
  private SqlTableConstraintArrays() { }

  static int limit(int kind) {
    if (kind == SqlTableConstraintSet.PRIMARY) return 1;
    if (kind == SqlTableConstraintSet.UNIQUE) return SqlShapeLimits.MAX_SECONDARY_INDEXES;
    if (kind == SqlTableConstraintSet.FOREIGN) return SqlShapeLimits.MAX_FOREIGN_KEYS;
    return kind == SqlTableConstraintSet.CHECK ? SqlShapeLimits.MAX_CHECK_CONSTRAINTS : -1;
  }

  static SqlIdentifier value(
      SqlIdentifier[] values, int[] starts, int[] counts,
      int constraintCount, int index, int part) {
    boolean valid = index >= 0 && index < constraintCount;
    int offset = valid && part >= 0 && part < counts[index] ? starts[index] + part : -1;
    return offset < 0 ? null : values[offset];
  }

  static void clear(
      int start, int end, SqlIdentifier[] first, SqlIdentifier[] second) {
    for (int index = start; index < end; index++) {
      first[index].reset();
      second[index].reset();
    }
  }

  static void copy(SqlIdentifier[] source, SqlIdentifier[] target, int count) {
    for (int index = 0; index < count; index++) target[index].copyFrom(source[index]);
  }

  static boolean same(CharSequence left, CharSequence right) {
    if (left.length() != right.length()) return false;
    for (int index = 0; index < left.length(); index++) {
      if (left.charAt(index) != right.charAt(index)) return false;
    }
    return true;
  }
}
