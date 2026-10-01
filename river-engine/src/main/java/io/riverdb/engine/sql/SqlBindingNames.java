package io.riverdb.engine.sql;

import io.riverdb.sql.SqlCommand;

/** Compares parser-owned identifiers without allocating normalized strings. */
final class SqlBindingNames {
  private SqlBindingNames() { }

  static boolean matchesTable(SqlCommand command, CharSequence qualifier) {
    return same(qualifier, command.tableName())
        || command.tableAlias().length() > 0
            && same(qualifier, command.tableAlias());
  }

  /** Exact parser spelling; an absent name never matches. */
  static boolean same(CharSequence left, CharSequence right) {
    if (left == null || right == null || left.length() != right.length()) {
      return false;
    }
    for (int index = 0; index < left.length(); index++) {
      if (left.charAt(index) != right.charAt(index)) {
        return false;
      }
    }
    return true;
  }
  /** Uppercase character folding used by legacy sort and descriptor index names. */
  static boolean sameIgnoringCase(CharSequence left, CharSequence right) {
    if (left == null || right == null || left.length() != right.length()) return false;
    for (int index = 0; index < left.length(); index++) {
      if (Character.toUpperCase(left.charAt(index))
          != Character.toUpperCase(right.charAt(index))) return false;
    }
    return true;
  }
}
