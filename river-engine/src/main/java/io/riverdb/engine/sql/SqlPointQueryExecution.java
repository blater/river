package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.sql.SqlCommandType;

/** Owns point-query execution and retryable physical resources. */
final class SqlPointQueryExecution {
  private final SqlPointAggregateExecution aggregates;
  private final SqlPointSelectExecution selects;

  SqlPointQueryExecution(
      RelationalSession session,
      BoundSqlStatement bound,
      SqlExpressionEvaluator expressions,
      SqlBoundPredicateEvaluator predicates,
      SqlRowProjectionEvaluator projections,
      SqlCurrentRowProtection currentRows,
      SqlTemporalContext temporal,
      SqlSessionShapeBudget shapeBudget) {
    aggregates = new SqlPointAggregateExecution(
        session, bound, expressions, predicates, projections, temporal, shapeBudget);
    selects = new SqlPointSelectExecution(
        session, bound, predicates, projections, currentRows);
  }

  StatusCode execute(SqlCommandType type, SqlExecutionResult result) {
    return aggregates.accepts(type)
        ? aggregates.execute(result) : selects.execute(result);
  }

  boolean hasResources() {
    return aggregates.hasResources() || selects.hasResources();
  }

  StatusCode closeResources() {
    StatusCode status = aggregates.closeResources();
    StatusCode selectStatus = selects.closeResources();
    return status.isOk() ? selectStatus : status;
  }

  void finishStatement() {
    aggregates.finishStatement();
  }
}
