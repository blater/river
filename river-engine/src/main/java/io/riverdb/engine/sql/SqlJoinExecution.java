package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalSession;

/** Publishes rows from the reusable physical JOIN source to a SQL scan. */
final class SqlJoinExecution {
  private final BoundSqlStatement bound;
  private final SqlPhysicalPlan plan;
  private final SqlJoinRowSource source;
  private final SqlRowProjectionEvaluator projections;

  SqlJoinExecution(
      RelationalSession session,
      BoundSqlStatement statement,
      SqlPhysicalPlan physicalPlan,
      SqlExpressionEvaluator expressions,
      SqlBoundPredicateEvaluator predicates,
      SqlRowProjectionEvaluator projectionEvaluator) {
    bound = statement;
    plan = physicalPlan;
    projections = projectionEvaluator;
    source = new SqlJoinRowSource(
        session, statement, expressions, predicates);
  }

  StatusCode begin() {
    StatusCode status = source.begin();
    if (status.isOk()) configurePlan(bound.executableQuery.root());
    return status;
  }

  StatusCode next(SqlScanCursor cursor, SqlScanRowResult result) {
    StatusCode status = source.next();
    if (status.isOk()) {
      status = projections.projectJoin(
          source.outerKey(),
          source.outerRow(),
          source.innerKey(),
          source.innerRow(),
          result);
    }
    if (status.isOk()) cursor.rowReturned();
    return status;
  }

  private void configurePlan(BoundSqlQuery.Block command) {
    plan.setFilterCount(bound.command.wherePredicates().leafCount());
    plan.setAccessColumn(source.outerValueIndex() ? bound.predicateColumn
        : bound.predicateColumn == 0 ? 0 : -1);
    plan.setJoin(
        bound.joinOuterColumn,
        bound.joinInnerColumn,
        bound.command.onPredicates().leafCount(),
        command.isLeftJoin(),
        source.innerIndexed(),
        source.innerUnique());
    for (int projection = 0;
        projection < bound.projectedColumnCount; projection++) {
      plan.setResultColumn(
          projection,
          bound.projectedColumns[projection],
          bound.projectionPrograms.resultDescriptor(projection),
          command.columnOutputName(projection));
      plan.setResultNullable(
          projection,
          SqlJoinResultNullability.nullable(bound, command.isLeftJoin(), projection));
    }
  }

  boolean hasResources() { return source.hasResources(); }

  StatusCode closeAfter(StatusCode prior) {
    return prior.isOk() && source.hasResources() ? source.close() : prior;
  }

  StatusCode close() {
    return source.close();
  }
}
