package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;

/** Applies one prepared block's residual and projection to a borrowed row. */
final class SqlBlockStageProjector {
  private final BoundSqlStatement bound;
  private final SqlRowProjectionEvaluator projections;
  private final SqlBlockPredicateEvaluator predicates;
  private final SqlBlockPredicateEvaluator.Match match =
      new SqlBlockPredicateEvaluator.Match();

  SqlBlockStageProjector(
      BoundSqlStatement statement,
      SqlExpressionEvaluator expressions,
      SqlRowProjectionEvaluator projectionEvaluator) {
    bound = statement;
    projections = projectionEvaluator;
    predicates = new SqlBlockPredicateEvaluator(expressions, projectionEvaluator);
  }

  StatusCode project(
      int block,
      SqlBlockRow source,
      SqlBlockRow destination,
      Projected result) {
    SqlBlockSchema child = block + 1 == bound.blockPlans().count()
        ? bound.blockPlans().baseSchema() : bound.blockPlans().schema(block + 1);
    StatusCode status = predicates.matches(
        bound.command, child, source, bound, match);
    if (!status.isOk()) return status;
    result.available = match.matched;
    return match.matched ? projections.projectBlock(source, destination) : StatusCode.OK;
  }

  void reset() { predicates.reset(); }

  static final class Projected { boolean available; }
}
