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
      SqlRowProjectionEvaluator projectionEvaluator,
      SqlTemporalContext temporal,
      SqlSessionShapeBudget shapeBudget) {
    bound = statement;
    projections = projectionEvaluator;
    predicates = new SqlBlockPredicateEvaluator(
        statement, expressions, temporal, shapeBudget);
  }

  StatusCode prepare(int block) {
    return bound.executableQuery.edgeCount() > 0
            && block == bound.executableQuery.sourceBlockCount() - 1
        ? StatusCode.OK : predicates.prepare(bound.command);
  }

  StatusCode project(
      int block,
      SqlBlockRow source,
      SqlBlockRow destination,
      Projected result) {
    boolean nestedSource = bound.executableQuery.edgeCount() > 0
        && block == bound.executableQuery.sourceBlockCount() - 1;
    StatusCode status = nestedSource
        ? StatusCode.OK : predicates.matches(bound.command, source, match);
    if (!status.isOk()) return status;
    result.available = nestedSource || match.matched;
    return result.available
        ? projections.projectBlock(source, destination, block) : StatusCode.OK;
  }

  void reset() { predicates.reset(); }

  static final class Projected { boolean available; }
}
