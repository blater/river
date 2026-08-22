package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.sql.SqlCommand;
/** Publishes rows from the reusable physical JOIN source to a SQL scan. */
final class SqlJoinExecution {
  private final BoundSqlStatement bound;
  private final SqlPhysicalPlan plan;
  private final SqlJoinChainSource source;
  private final SqlJoinPredicateCallback defaultPredicates;
  private final SqlRowProjectionEvaluator projections;
  private final SqlJoinChainPlan stages;
  private SqlBoundJoinContext context;
  private SqlCommand command;

  SqlJoinExecution(
      BoundSqlStatement statement,
      SqlPhysicalPlan physicalPlan,
      SqlJoinChainSource rowSource,
      SqlJoinPredicateCallback predicateEvaluator,
      SqlRowProjectionEvaluator projectionEvaluator,
      SqlJoinChainPlan joinPlan) {
    bound = statement;
    plan = physicalPlan;
    projections = projectionEvaluator;
    source = rowSource;
    defaultPredicates = predicateEvaluator;
    stages = joinPlan;
  }

  StatusCode configure(
      SqlCommand canonicalCommand,
      SqlBoundJoinContext joinContext) {
    return configure(
        canonicalCommand, joinContext, bound.whereBoolean, null);
  }

  StatusCode configure(
      SqlCommand canonicalCommand,
      SqlBoundJoinContext joinContext,
      SqlBoundBooleanPredicateProgram where,
      SqlJoinPredicateCallback predicates) {
    command = canonicalCommand;
    context = joinContext;
    return source.configure(
        context,
        command,
        where,
        predicates == null ? defaultPredicates : predicates);
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

  private StatusCode configurePlan(BoundSqlQuery.Block executable) {
    plan.setFilterCount(command.wherePredicates().leafCount());
    plan.setSort(executable.isOrdered());
    for (int projection = 0;
        projection < bound.projectedColumnCount; projection++) {
      plan.setResultColumn(
          projection,
          bound.projectedColumns[projection],
          bound.projectionPrograms.resultDescriptor(projection),
          executable.columnOutputName(projection));
      plan.setResultNullable(
          projection,
          SqlJoinResultNullability.nullable(command, context, bound, projection));
    }
    StatusCode status = stages.describe(
        command, context, source, bound.executableQuery.isAnalyze());
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
