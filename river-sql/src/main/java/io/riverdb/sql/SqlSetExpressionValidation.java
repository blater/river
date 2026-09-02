package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Cross-operand shape and output-name rules that do not require bound SQL types. */
final class SqlSetExpressionValidation {
  private SqlSetExpressionValidation() { }

  static StatusCode validateArity(SqlQuery query) {
    SqlCommand first = query.firstSetBlock();
    if (first == null || first.isSelectAll()) return StatusCode.OK;
    int columns = first.columnCount();
    for (int node = 0; node < query.setNodeCount(); node++) {
      if (query.setNodeKind(node) != SqlQuery.SET_LEAF) continue;
      SqlCommand current = query.block(query.setLeafBlock(node));
      if (!current.isSelectAll() && current.columnCount() != columns) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
    }
    return StatusCode.OK;
  }

  static boolean selected(SqlCommand command, CharSequence name) {
    if (command == null) return false;
    if (command.isSelectAll()) return true;
    for (int column = 0; column < command.columnCount(); column++) {
      if (same(command.columnOutputName(column), name)) return true;
    }
    return false;
  }

  private static boolean same(CharSequence left, CharSequence right) {
    if (left == null || right == null || left.length() != right.length()) return false;
    for (int index = 0; index < left.length(); index++) {
      if (SqlParserInput.upper(left.charAt(index))
          != SqlParserInput.upper(right.charAt(index))) return false;
    }
    return true;
  }
}
