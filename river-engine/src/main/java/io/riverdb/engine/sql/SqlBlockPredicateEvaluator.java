package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.sql.SqlCommand;

/** Evaluates one block-local canonical Boolean predicate over an owned row. */
final class SqlBlockPredicateEvaluator {
  private final BoundSqlStatement bound;
  private final SqlBooleanPredicateEvaluator predicates;
  private final SqlBooleanPredicateEvaluator.Match match =
      new SqlBooleanPredicateEvaluator.Match();

  SqlBlockPredicateEvaluator(
      BoundSqlStatement statement,
      SqlExpressionEvaluator expressions,
      SqlTemporalContext temporal,
      SqlSessionShapeBudget shapeBudget) {
    bound = statement;
    predicates = new SqlBooleanPredicateEvaluator(expressions, temporal, shapeBudget);
  }

  StatusCode prepare(SqlCommand command) {
    return predicates.prepare(command, bound.whereBoolean);
  }

  StatusCode matches(SqlCommand command, SqlBlockRow row, Match result) {
    StatusCode status = predicates.matchesBlock(
        command, bound.whereBoolean, row, match);
    result.matched = match.matched();
    return status;
  }

  void reset() { predicates.reset(); }

  static final class Match { boolean matched; }
}
