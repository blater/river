package io.riverdb.engine.sql;

/** Exact nullable fixed/text equality for one retained block-row column. */
final class SqlBlockRowEquality {
  private SqlBlockRowEquality() { }

  static boolean same(SqlBlockRow left, SqlBlockRow right, int column) {
    if (left.nullValue(column) != right.nullValue(column)) return false;
    if (left.nullValue(column)) return true;
    int length = left.textLength(column);
    if (length != right.textLength(column)) return false;
    if (length == 0) {
      return left.highValue(column) == right.highValue(column)
          && left.value(column) == right.value(column);
    }
    for (int index = 0; index < length; index++) {
      if (left.textCharacter(column, index) != right.textCharacter(column, index)) return false;
    }
    return true;
  }

  static boolean same(SqlBlockRow left, SqlBlockRow right, int first, int count) {
    for (int column = first; column < first + count; column++) {
      if (!same(left, right, column)) return false;
    }
    return true;
  }
}
