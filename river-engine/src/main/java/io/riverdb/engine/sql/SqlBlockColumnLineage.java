package io.riverdb.engine.sql;

import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlCommandType;
import io.riverdb.sql.SqlScalarExpression;

/** Maps a root predicate lane through a direct projection chain to the base table. */
final class SqlBlockColumnLineage {
  private SqlBoundBlockPlans plans;

  void prepare(SqlBoundBlockPlans blockPlans) {
    plans = blockPlans;
  }

  int baseColumn(int rootChildColumn) {
    if (plans == null || plans.count() < 2 || rootChildColumn < 0) return -1;
    int column = rootChildColumn;
    for (int block = 1; block < plans.count(); block++) {
      SqlCommand command = plans.command(block);
      if (!projectionOnly(command) || column >= command.columnCount()) return -1;
      SqlScalarExpression expression = command.projectionExpression(column);
      if (expression == null || !expression.isDirectColumnReference()) return -1;
      int symbol = (int) expression.operand(0);
      CharSequence name = command.projectionSymbolName(symbol);
      SqlBlockSchema child = block + 1 < plans.count()
          ? plans.schema(block + 1) : plans.baseSchema();
      column = name == null ? -1 : child.find(name);
      if (column < 0) return -1;
    }
    return column;
  }

  private static boolean projectionOnly(SqlCommand command) {
    SqlCommandType type = command == null ? null : command.type();
    return (type == SqlCommandType.SELECT || type == SqlCommandType.SCAN)
        && command.aggregateInvocationCount() == 0
        && command.rowLimit() == Long.MAX_VALUE;
  }
}
