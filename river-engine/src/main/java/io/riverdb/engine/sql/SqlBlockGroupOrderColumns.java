package io.riverdb.engine.sql;

import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlScalarExpression;

/** Maps private grouped ORDER BY columns onto canonical group-key lanes. */
final class SqlBlockGroupOrderColumns {
  private SqlBlockGroupOrderColumns() { }

  static int hiddenCount(SqlCommand command) {
    if (command.grouping().count() == 0) return 0;
    int count = 0;
    for (int order = 0; order < command.orderBy().count(); order++) {
      CharSequence name = command.orderBy().name(order);
      if (!selected(command, name) && !prior(command, order, name)) count++;
    }
    return count;
  }

  static int group(SqlCommand command, CharSequence name) {
    for (int group = 0; group < command.grouping().count(); group++) {
      SqlScalarExpression expression = command.grouping().expression(group);
      if (expression == null || !expression.isDirectColumnReference()) continue;
      int symbol = (int) expression.operand(0);
      if (SqlBindingNames.same(command.projections().symbolName(symbol), name)) return group;
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
      if (SqlBindingNames.same(command.orderBy().name(prior), name)) return true;
    }
    return false;
  }
}
