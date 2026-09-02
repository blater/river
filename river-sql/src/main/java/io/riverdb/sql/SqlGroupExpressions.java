package io.riverdb.sql;

/** Shared structural matching for selected and grouped scalar expressions. */
public final class SqlGroupExpressions {
  private SqlGroupExpressions() { }

  public static int groupKey(SqlCommand command, int projection) {
    if (command == null || projection < 0) return -1;
    for (int group = 0; group < command.groupExpressionCount(); group++) {
      if (matchesProjection(command, group, projection)) return group;
    }
    return -1;
  }

  static int groupKey(
      SqlCommand command, boolean grouped, int projection, CharSequence name) {
    if (!grouped) return -1;
    return projection >= 0
        ? groupKey(command, projection) : namedGroupKey(command, name);
  }

  static boolean resolves(
      SqlCommand command, boolean grouped, int projection, CharSequence name) {
    return projection >= 0 || groupKey(command, grouped, projection, name) >= 0;
  }

  public static int namedGroupKey(SqlCommand command, CharSequence name) {
    if (command == null || name == null) return -1;
    for (int group = 0; group < command.groupExpressionCount(); group++) {
      SqlScalarExpression expression = command.groupExpression(group);
      if (expression == null || !expression.isDirectColumnReference()) continue;
      int symbol = (int) expression.operand(0);
      if (same(command.projectionSymbolName(symbol), name)) return group;
    }
    return -1;
  }

  public static boolean matchesProjection(
      SqlCommand command, int group, int projection) {
    if (command == null) return false;
    SqlScalarExpression grouped = command.groupExpression(group);
    SqlScalarExpression selected = command.projectionExpression(projection);
    return grouped != null && selected != null
        && SqlAggregateExpressionParser.same(command, selected, grouped);
  }

  private static boolean same(CharSequence left, CharSequence right) {
    if (left == null || right == null || left.length() != right.length()) return false;
    for (int index = 0; index < left.length(); index++) {
      if (left.charAt(index) != right.charAt(index)) return false;
    }
    return true;
  }
}
