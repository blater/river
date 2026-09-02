package io.riverdb.engine.sql;

import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlCommandType;

/** Admits scalar JOIN aggregates that consume unmodified child lanes. */
final class SqlScalarJoinFusionPolicy {
  private SqlScalarJoinFusionPolicy() {}

  static boolean admits(BoundSqlStatement bound, SqlBoundBlockPlans plans) {
    if (plans.count() != 2) return false;
    SqlCommand root = plans.command(0);
    SqlCommand join = plans.command(1);
    if (join.type() != SqlCommandType.JOIN_SCAN
        || join.isOrdered()
        || join.rowLimit() != Long.MAX_VALUE
        || join.aggregateInvocationCount() != 0
        || !SqlBinder.isScalarAggregate(root.type())
        || root.groupExpressionCount() != 0
        || root.aggregateInvocationCount() == 0
        || root.wherePredicates().leafCount() != 0) {
      return false;
    }
    for (int invocation = 0;
        invocation < root.aggregateInvocationCount(); invocation++) {
      int source = root.aggregateOperandProjection(invocation);
      int lane = bound.aggregates.operandLane(invocation);
      if (source >= 0 && (!root.aggregateOperandExpression(source).isDirectColumnReference()
          || lane < 0 || bound.projectionPrograms.rawColumn(lane) < 0)) {
        return false;
      }
    }
    return true;
  }
}
