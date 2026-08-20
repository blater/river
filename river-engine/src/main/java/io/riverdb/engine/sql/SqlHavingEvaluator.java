package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.sql.SqlCommand;

/** Evaluates HAVING through the common bounded Boolean predicate evaluator. */
final class SqlHavingEvaluator {
  private final BoundSqlStatement bound;
  private final SqlBooleanPredicateEvaluator predicates;
  private final SqlBooleanPredicateEvaluator.Match result =
      new SqlBooleanPredicateEvaluator.Match();

  SqlHavingEvaluator(
      BoundSqlStatement statement,
      SqlExpressionEvaluator expressions,
      SqlTemporalContext temporal) {
    bound = statement;
    predicates = new SqlBooleanPredicateEvaluator(expressions, temporal);
  }

  StatusCode prepare(SqlCommand command) {
    return predicates.prepare(command, bound.havingBoolean);
  }

  StatusCode evaluate(
      SqlCommand command,
      SqlAggregateAccumulatorSet aggregates,
      long groupValue,
      boolean groupNull,
      byte[] groupText,
      int groupTextLength) {
    return predicates.matchesHaving(
        command,
        bound.havingBoolean,
        aggregates,
        groupValue,
        groupNull,
        groupText,
        groupTextLength,
        result);
  }

  boolean matched() { return result.matched(); }

  void reset() { predicates.reset(); }
}
