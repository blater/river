package io.riverdb.sql;

/** Resolves bounded ORDER BY names against selected or grouped expressions. */
final class SqlOrderByNames {
  boolean valid(SqlCommand command, CharSequence qualifier, CharSequence name) {
    return !requiresOutput(command.type())
        || selected(command, qualifier, name) || grouped(command, qualifier, name);
  }

  private static boolean selected(
      SqlCommand command, CharSequence qualifier, CharSequence name) {
    for (int output = 0; output < command.columnCount(); output++) {
      if (qualifier.length() == 0) {
        if (same(command.columnOutputName(output), name)
            || same(command.columnName(output), name)) return true;
      } else if (same(command.columnTableName(output), qualifier)
          && same(command.columnName(output), name)) return true;
    }
    return false;
  }

  private static boolean grouped(
      SqlCommand command, CharSequence qualifier, CharSequence name) {
    for (int group = 0; group < command.groupExpressionCount(); group++) {
      SqlScalarExpression expression = command.groupExpression(group);
      if (expression == null || !expression.isDirectColumnReference()) continue;
      int symbol = (int) expression.operand(0);
      if (same(command.projectionSymbolName(symbol), name)
          && (qualifier.length() == 0
              || same(command.projectionSymbolTable(symbol), qualifier))) return true;
    }
    return false;
  }

  private static boolean requiresOutput(SqlCommandType type) {
    return type == SqlCommandType.DISTINCT_SCAN
        || type == SqlCommandType.GROUP_COUNT
        || type == SqlCommandType.GROUP_COUNT_VALUE
        || type == SqlCommandType.GROUP_COUNT_DISTINCT
        || type == SqlCommandType.GROUP_SUM
        || type == SqlCommandType.GROUP_AVG
        || type == SqlCommandType.GROUP_MIN
        || type == SqlCommandType.GROUP_MAX;
  }

  private static boolean same(CharSequence left, CharSequence right) {
    if (left == null || right == null || left.length() != right.length()) return false;
    for (int index = 0; index < left.length(); index++) {
      char first = left.charAt(index);
      char second = right.charAt(index);
      if (first != second && Character.toUpperCase(first) != Character.toUpperCase(second)) {
        return false;
      }
    }
    return true;
  }
}
