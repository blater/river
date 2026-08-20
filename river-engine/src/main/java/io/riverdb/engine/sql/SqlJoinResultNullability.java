package io.riverdb.engine.sql;

import io.riverdb.sql.SqlScalarExpression;

/** Computes JOIN projection nullability from scoped source metadata. */
final class SqlJoinResultNullability {
  private SqlJoinResultNullability() {}

  static boolean nullable(
      BoundSqlStatement bound, boolean leftJoin, int projection) {
    for (int node = 0;
        node < bound.projectionPrograms.nodeCount(projection); node++) {
      int operator = bound.projectionPrograms.operator(projection, node);
      if (operator == SqlScalarExpression.NULL) return true;
      if (operator != SqlScalarExpression.COLUMN) continue;
      int column = (int) bound.projectionPrograms.operand(projection, node);
      int scope = bound.projectionPrograms.scope(projection, node);
      if (scope == SqlBoundBooleanPredicateProgram.SCOPE_RIGHT) {
        if (leftJoin || bound.joinTable.isNullable(column)) return true;
      } else if (bound.table.isNullable(column)) {
        return true;
      }
    }
    return false;
  }
}
