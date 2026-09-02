package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;

/** Clears reusable point-statement evaluators after execution. */
final class SqlPointStatementFinish {
  private SqlPointStatementFinish() {}

  static StatusCode finish(
      SqlBoundPredicateEvaluator predicates,
      SqlSubqueryGraphExecution subqueries,
      SqlRowProjectionEvaluator projections,
      SqlPointQueryExecution points) {
    predicates.reset();
    StatusCode status = subqueries.reset();
    projections.reset();
    points.finishStatement();
    return status;
  }
}
