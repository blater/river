package io.riverdb.engine.sql;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;
import io.riverdb.sql.SqlJoinChain;

/** Transactionally admitted, high-water retained JOIN-stage predicate evaluators. */
final class SqlJoinPredicateEvaluators {
  private final SqlBooleanPredicateWorkspace workspace;
  private final SqlTemporalContext temporal;
  private final SqlSessionShapeBudget budget;
  private SqlBooleanPredicateEvaluator[] values = new SqlBooleanPredicateEvaluator[0];

  SqlJoinPredicateEvaluators(
      SqlBooleanPredicateWorkspace shared,
      SqlTemporalContext temporalContext,
      SqlSessionShapeBudget shapeBudget) {
    workspace = shared;
    temporal = temporalContext;
    budget = shapeBudget;
  }

  StatusCode prepare(int required) {
    int capacity = BoundedArrayGrowth.capacity(
        values.length, required, SqlJoinChain.MAXIMUM_JOIN_STAGES, 1);
    if (capacity < 0) return StatusCode.RESOURCE_EXHAUSTED;
    if (capacity == values.length) return StatusCode.OK;
    try {
      SqlBooleanPredicateEvaluator[] next = new SqlBooleanPredicateEvaluator[capacity];
      System.arraycopy(values, 0, next, 0, values.length);
      for (int stage = values.length; stage < capacity; stage++) {
        next[stage] = new SqlBooleanPredicateEvaluator(workspace, temporal, budget);
      }
      values = next;
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  SqlBooleanPredicateEvaluator get(int stage) { return values[stage]; }

  void reset() {
    for (SqlBooleanPredicateEvaluator evaluator : values) evaluator.reset();
  }
}
