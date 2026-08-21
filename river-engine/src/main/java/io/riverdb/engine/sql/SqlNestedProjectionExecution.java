package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlQuery;
import io.riverdb.sql.SqlScalarExpression;

/** Preflights and evaluates one scoped child result per subquery graph block. */
final class SqlNestedProjectionExecution {
  private final BoundSqlStatement bound;
  private final SqlExpressionEvaluator expressions;
  private final SqlTemporalContext temporal;
  private final SqlRowExpressionEvaluator[] evaluators =
      new SqlRowExpressionEvaluator[SqlQuery.MAXIMUM_QUERY_BLOCKS];
  private final SqlTemporalZonePlan[] zones =
      new SqlTemporalZonePlan[SqlQuery.MAXIMUM_QUERY_BLOCKS];
  private final SqlTemporalContext.LongResult current =
      new SqlTemporalContext.LongResult();

  SqlNestedProjectionExecution(
      BoundSqlStatement statement,
      SqlExpressionEvaluator evaluator,
      SqlTemporalContext temporalContext) {
    bound = statement;
    expressions = evaluator;
    temporal = temporalContext;
  }

  StatusCode prepare(int block) {
    if (evaluators[block] == null) {
      evaluators[block] = new SqlRowExpressionEvaluator(expressions, temporal);
    }
    SqlBoundProjectionPrograms programs = bound.nestedProjection(block);
    SqlCommand command = bound.query.block(block);
    int zoneCount = 0;
    for (int node = 0; node < programs.nodeCount(0); node++) {
      int operator = programs.operator(0, node);
      if (operator >= SqlScalarExpression.CURRENT_DATE
          && operator <= SqlScalarExpression.LOCALTIMESTAMP) {
        StatusCode status = temporal.currentValue(
            operator, programs.descriptor(0, node), current);
        if (!status.isOk()) return status;
      }
      if (operator != SqlScalarExpression.AT_TIME_ZONE) continue;
      if (++zoneCount > 1) return StatusCode.FEATURE_NOT_SUPPORTED;
      if (zones[block] == null) zones[block] = new SqlTemporalZonePlan();
      StatusCode status = temporal.prepareZone(
          command, programs.operand(0, node), zones[block]);
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  StatusCode evaluate(
      int block, SqlNestedRowProvider rows, SqlPredicateOperand result) {
    return evaluators[block].evaluateNestedOperand(
        bound.query.block(block),
        bound.nestedProjection(block),
        zones[block],
        rows,
        result);
  }

  void reset() {
    for (int block = 0; block < evaluators.length; block++) {
      if (evaluators[block] != null) evaluators[block].reset();
      if (zones[block] != null) {
        zones[block].reset();
        zones[block] = null;
      }
    }
    current.value = 0;
  }
}
