package io.riverdb.engine.sql;

import io.riverdb.sql.SqlAggregateKind;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlScalarExpression;

/** Owns the narrow semantic admission for canonical dependent primary-key probes. */
final class SqlKeyOrderedLookupAdmission {
  private SqlKeyOrderedLookupAdmission() { }

  static int projectedInnerColumn(
      BoundSqlStatement bound, SqlBoundBlockPlans plans, int block,
      boolean fusedScalarJoin) {
    if (!fusedScalarJoin || plans.count() != 2 || block != 1
        || bound.joinedAggregates.count() != 1
        || bound.joinedAggregates.kind(0) != SqlAggregateKind.COUNT_DISTINCT) {
      return -1;
    }
    SqlCommand command = plans.command(block);
    if (command.joinChain() == null || command.joinChain().stageCount() != 1
        || command.joinChain().isLeft(0)) {
      return -1;
    }
    int lane = bound.joinedAggregates.operandLane(0);
    SqlBoundProjectionPrograms projections = bound.projectionPrograms;
    if (lane < 0 || lane >= projections.count() || projections.nodeCount(lane) != 1
        || projections.operator(lane, 0) != SqlScalarExpression.COLUMN
        || projections.scope(lane, 0) != SqlBoundBooleanPredicateProgram.SCOPE_RIGHT) {
      return -1;
    }
    long column = projections.operand(lane, 0);
    return column < 0 || column > Integer.MAX_VALUE ? -1 : (int) column;
  }
}
