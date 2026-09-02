package io.riverdb.engine.sql;

import io.riverdb.sql.SqlCommandType;

/** Small lexical and command-kind predicates used by the session gate. */
final class SqlSessionCommandKinds {
  private static final String SELECT = "SELECT";

  private SqlSessionCommandKinds() { }

  static boolean query(SqlCommandType type) {
    return type == SqlCommandType.SHOW_TABLES
        || type == SqlCommandType.SHOW_INDEXES
        || type == SqlCommandType.SHOW_COLUMNS
        || type == SqlCommandType.SCAN
        || type == SqlCommandType.SELECT
        || type == SqlCommandType.DISTINCT_SCAN
        || type == SqlCommandType.JOIN_SCAN
        || type == SqlCommandType.NEXT_SEQUENCE_VALUE
        || type == SqlCommandType.SCALAR_EXPRESSION
        || SqlBinder.isScalarAggregate(type)
        || SqlBinder.isGroupAggregate(type);
  }

  static boolean beginsSelect(String sql) {
    if (sql == null) return false;
    int index = 0;
    while (index < sql.length() && Character.isWhitespace(sql.charAt(index))) index++;
    if (sql.length() - index < SELECT.length()) return false;
    for (int offset = 0; offset < SELECT.length(); offset++) {
      if (Character.toUpperCase(sql.charAt(index + offset)) != SELECT.charAt(offset)) {
        return false;
      }
    }
    int end = index + SELECT.length();
    return end == sql.length()
        || !Character.isLetterOrDigit(sql.charAt(end)) && sql.charAt(end) != '_';
  }
}
