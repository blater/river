package io.riverdb.engine.sql;

import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlScalarExpression;

/** Maps private grouped ORDER BY columns onto canonical group-key lanes. */
final class SqlBlockGroupOrderColumns {
  private SqlBlockGroupOrderColumns() { }

  static int hiddenCount(SqlCommand command) {
    if (command.groupExpressionCount() == 0) return 0;
    int count = 0;
    for (int order = 0; order < command.orderExpressionCount(); order++) {
      CharSequence name = command.orderColumnName(order);
      if (!selected(command, name) && !prior(command, order, name)) count++;
    }
    return count;
  }

  static int group(SqlCommand command, CharSequence name) {
    for (int group = 0; group < command.groupExpressionCount(); group++) {
      SqlScalarExpression expression = command.groupExpression(group);
      if (expression == null || !expression.isDirectColumnReference()) continue;
      int symbol = (int) expression.operand(0);
      if (SqlBindingNames.same(command.projectionSymbolName(symbol), name)) return group;
    }
    return -1;
  }

  static boolean selected(SqlCommand command, CharSequence name) {
    for (int column = 0; column < command.columnCount(); column++) {
      if (SqlBindingNames.same(command.columnOutputName(column), name)
          || SqlBindingNames.same(command.columnName(column), name)) return true;
    }
    return false;
  }

  private static boolean prior(SqlCommand command, int order, CharSequence name) {
    for (int prior = 0; prior < order; prior++) {
      if (SqlBindingNames.same(command.orderColumnName(prior), name)) return true;
    }
    return false;
  }
}
