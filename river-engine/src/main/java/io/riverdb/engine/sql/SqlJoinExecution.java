package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
/** Publishes rows from the reusable physical JOIN source to a SQL scan. */
final class SqlJoinExecution {
  private final BoundSqlStatement bound;
  private final SqlPhysicalPlan plan;
  private final SqlJoinChainSource source;
  private final SqlRowProjectionEvaluator projections;
  private final SqlJoinChainPlan stages;

  SqlJoinExecution(
      BoundSqlStatement statement,
      SqlPhysicalPlan physicalPlan,
      SqlJoinChainSource rowSource,
      SqlRowProjectionEvaluator projectionEvaluator,
      SqlJoinChainPlan joinPlan) {
    bound = statement;
    plan = physicalPlan;
    projections = projectionEvaluator;
    source = rowSource;
    stages = joinPlan;
  }

  StatusCode begin() {
    if (bound.executableQuery.root().rowLimit() == 0) {
      source.resetMetrics();
      return configurePlan(bound.executableQuery.root());
    }
    StatusCode status = source.begin();
    if (status.isOk()) status = configurePlan(bound.executableQuery.root());
    return status;
  }

  StatusCode describe() {
    return configurePlan(bound.executableQuery.root());
  }

  StatusCode next(SqlScanCursor cursor, SqlScanRowResult result) {
    StatusCode status = source.next();
    if (status.isOk()) {
      status = projections.projectJoin(
          source.rows(),
          result);
    }
    if (status.isOk()) cursor.rowReturned();
    return status;
  }

  private StatusCode configurePlan(BoundSqlQuery.Block command) {
    plan.setFilterCount(bound.command.wherePredicates().leafCount());
    plan.setSort(command.isOrdered());
    for (int projection = 0;
        projection < bound.projectedColumnCount; projection++) {
      plan.setResultColumn(
          projection,
          bound.projectedColumns[projection],
          bound.projectionPrograms.resultDescriptor(projection),
          command.columnOutputName(projection));
      plan.setResultNullable(
          projection,
          SqlJoinResultNullability.nullable(bound, projection));
    }
    StatusCode status = stages.describe(
        bound, source, bound.executableQuery.isAnalyze());
    if (status.isOk()) plan.setJoinStages(stages);
    return status;
  }

  boolean hasResources() { return source.hasResources(); }

  StatusCode closeAfter(StatusCode prior) {
    return prior.isOk() && source.hasResources() ? source.close() : prior;
  }

  StatusCode close() {
    return source.close();
  }
}
