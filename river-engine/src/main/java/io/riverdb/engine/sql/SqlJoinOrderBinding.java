package io.riverdb.engine.sql;

import io.riverdb.sql.SqlCommand;

/** Resolves every joined ORDER BY output and returns its first projection. */
final class SqlJoinOrderBinding {
  private SqlJoinOrderBinding() {}

  static int firstProjection(SqlCommand command) {
    int first = -1;
    for (int expression = 0;
        expression < command.orderExpressionCount(); expression++) {
      int projection = SqlProjectionBinder.resolveOrderAlias(command, expression);
      if (command.orderColumnTableName(expression).length() > 0) {
        projection = SqlProjectionBinder.resolveOrderProjection(command, expression);
      }
      if (projection < 0) return -1;
      if (expression == 0) first = projection;
    }
    return first;
  }
}
