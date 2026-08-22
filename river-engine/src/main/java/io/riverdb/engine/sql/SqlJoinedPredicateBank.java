package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.sql.SqlQuery;

/** Lazily owns one prepared joined predicate evaluator per used graph block. */
final class SqlJoinedPredicateBank {
  private final SqlJoinedPredicateEvaluator[] evaluators =
      new SqlJoinedPredicateEvaluator[SqlQuery.MAXIMUM_QUERY_BLOCKS];
  private final BoundSqlStatement bound;
  private final SqlExpressionEvaluator expressions;
  private final SqlTemporalContext temporal;
  private final SqlSubqueryLeafEvaluator leaves;
  private final SqlNestedRowProvider rows;
  private final SqlSubqueryPlan plan;

  SqlJoinedPredicateBank(
      BoundSqlStatement statement,
      SqlExpressionEvaluator evaluator,
      SqlTemporalContext temporalContext,
      SqlSubqueryLeafEvaluator leafEvaluator,
      SqlNestedRowProvider rowProvider,
      SqlSubqueryPlan subqueryPlan) {
    bound = statement;
    expressions = evaluator;
    temporal = temporalContext;
    leaves = leafEvaluator;
    rows = rowProvider;
    plan = subqueryPlan;
  }

  StatusCode prepare(int block) {
    if (evaluators[block] == null) {
      evaluators[block] = new SqlJoinedPredicateEvaluator(
          block, expressions, temporal, leaves, rows, plan);
    }
    return evaluators[block].configureJoin(
        bound.query.block(block),
        bound.existingJoinContext(block),
        bound.nestedBoolean(block));
  }

  SqlJoinPredicateCallback evaluator(int block) {
    return block < 0 || block >= evaluators.length ? null : evaluators[block];
  }

  void reset() {
    for (SqlJoinedPredicateEvaluator evaluator : evaluators) {
      if (evaluator != null) evaluator.reset();
    }
  }
}
