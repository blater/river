package io.riverdb.engine.sql;

import io.riverdb.sql.SqlScalarExpression;

/** Computes JOIN projection nullability from scoped source metadata. */
final class SqlJoinResultNullability {
  private SqlJoinResultNullability() {}

  static boolean nullable(BoundSqlStatement bound, int projection) {
    for (int node = 0;
        node < bound.projectionPrograms.nodeCount(projection); node++) {
      int operator = bound.projectionPrograms.operator(projection, node);
      if (operator == SqlScalarExpression.NULL) return true;
      if (operator != SqlScalarExpression.COLUMN) continue;
      int column = (int) bound.projectionPrograms.operand(projection, node);
      int role = bound.projectionPrograms.scope(projection, node);
      if (role > 0 && bound.command.joinChain().isLeft(role - 1)
          || bound.joinRole(role).isNullable(column)) {
        return true;
      }
    }
    return false;
  }
}
