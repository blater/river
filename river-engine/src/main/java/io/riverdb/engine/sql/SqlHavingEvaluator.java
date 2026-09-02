package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.sql.SqlCommand;

/** Evaluates HAVING through the common bounded Boolean predicate evaluator. */
final class SqlHavingEvaluator {
  private final BoundSqlStatement bound;
  private final SqlBooleanPredicateEvaluator predicates;
  private final SqlBooleanPredicateEvaluator.Match result =
      new SqlBooleanPredicateEvaluator.Match();
  private final SqlHavingGroup group = new SqlHavingGroup();

  SqlHavingEvaluator(
      BoundSqlStatement statement,
      SqlExpressionEvaluator expressions,
      SqlTemporalContext temporal) {
    this(statement, expressions, temporal, new SqlSessionShapeBudget(null));
  }

  SqlHavingEvaluator(
      BoundSqlStatement statement,
      SqlExpressionEvaluator expressions,
      SqlTemporalContext temporal,
      SqlSessionShapeBudget shapeBudget) {
    bound = statement;
    predicates = new SqlBooleanPredicateEvaluator(expressions, temporal, shapeBudget);
  }

  StatusCode prepare(SqlCommand command) {
    return predicates.prepare(command, bound.havingBoolean);
  }

  StatusCode prepare(
      SqlCommand command,
      SqlAggregateAccumulatorSet accumulators,
      SqlBoundAggregateSet aggregates) {
    StatusCode status = SqlAggregateAccumulatorCapacity.reserve(
        accumulators, aggregates);
    return status.isOk() ? prepare(command) : status;
  }

  StatusCode prepare(
      SqlCommand command,
      SqlAggregateAccumulatorSet accumulators,
      SqlAggregateAccumulatorSet lookahead,
      SqlBoundAggregateSet aggregates) {
    StatusCode status = SqlAggregateAccumulatorCapacity.reservePair(
        accumulators, lookahead, aggregates);
    return status.isOk() ? prepare(command) : status;
  }

  StatusCode evaluate(
      SqlCommand command,
      SqlAggregateAccumulatorSet aggregates,
      long groupValue,
      boolean groupNull,
      byte[] groupText,
      int groupTextLength) {
    group.useScalar(groupValue, groupNull, groupText, groupTextLength);
    return evaluate(command, aggregates);
  }

  StatusCode evaluate(
      SqlCommand command,
      SqlAggregateAccumulatorSet aggregates,
      SqlBlockRow row,
      int count) {
    group.useRow(row, count);
    return evaluate(command, aggregates);
  }

  private StatusCode evaluate(
      SqlCommand command, SqlAggregateAccumulatorSet aggregates) {
    return predicates.matchesHaving(
        command,
        bound.havingBoolean,
        aggregates,
        group,
        result);
  }

  boolean matched() { return result.matched(); }

  void reset() {
    group.clear();
    predicates.reset();
  }
}
