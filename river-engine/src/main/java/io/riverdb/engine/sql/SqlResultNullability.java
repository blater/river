package io.riverdb.engine.sql;

import io.riverdb.sql.SqlScalarExpression;

/** Derives result nullability from one fully bound projection program. */
final class SqlResultNullability {
  private SqlResultNullability() {}

  static boolean projection(BoundSqlStatement bound, int index) {
    int projection = bound.projectedColumns[index];
    if (projection == BoundSqlStatement.NULL_PROJECTION) return true;
    if (projection == SqlBoundProjectionPrograms.COMPUTED_PROJECTION) {
      return program(bound, index);
    }
    return bound.table.isNullable(projection);
  }

  static boolean program(BoundSqlStatement bound, int lane) {
    SqlBoundProjectionPrograms programs = bound.projectionPrograms;
    for (int node = 0; node < programs.nodeCount(lane); node++) {
      int operator = programs.operator(lane, node);
      if (operator == SqlScalarExpression.NULL) return true;
      if (operator == SqlScalarExpression.COLUMN
          && bound.table.isNullable((int) programs.operand(lane, node))) {
        return true;
      }
    }
    return false;
  }
}
