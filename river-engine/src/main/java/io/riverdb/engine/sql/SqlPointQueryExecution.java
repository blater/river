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
      SqlRowProjectionEvaluator projections) {
    aggregates = new SqlPointAggregateExecution(
        session, bound, expressions, predicates, projections);
    selects = new SqlPointSelectExecution(session, bound, predicates, projections);
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
    return status.isOk() ? selects.closeResources() : status;
  }
}
