package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.sql.SqlCommand;

/** Lifecycle and publication for descriptor/mixed universal nested-loop joins. */
final class SqlUniversalJoinExecution {
  private final SqlUniversalJoinRows rows;
  private final SqlUniversalJoinPredicates predicates;
  private final SqlUniversalJoinSource source;
  private final SqlRowProjectionEvaluator projections;
  private final SqlJoinChainPlan stages;
  private SqlBoundJoinContext context;
  private boolean matched;
  private boolean active;

  SqlUniversalJoinExecution(
      RelationalSession session,
      SqlExpressionEvaluator expressions,
      SqlTemporalContext temporal,
      SqlRowProjectionEvaluator projectionEvaluator,
      SqlJoinChainPlan joinPlan,
      SqlSessionShapeBudget shapeBudget) {
    rows = new SqlUniversalJoinRows(session);
    source = new SqlUniversalJoinSource(session, shapeBudget);
    predicates = new SqlUniversalJoinPredicates(expressions, temporal, shapeBudget);
    projections = projectionEvaluator;
    stages = joinPlan;
  }

  StatusCode resolve(SqlCommand command, SqlBoundJoinContext joinContext) {
    StatusCode status = rows.resolve(command, joinContext);
    matched = status.isOk() && rows.matched();
    if (matched) context = joinContext;
    return status;
  }

  StatusCode configure(BoundSqlStatement bound, SqlPhysicalPlan plan) {
    if (!matched || context == null) return StatusCode.CONFLICT;
    StatusCode status = predicates.prepare(
        bound.command, context, bound.whereBoolean);
    if (status.isOk()) {
      rows.configureAccess(bound.command, context, bound.whereBoolean);
      source.configure(bound.command, context, bound.whereBoolean, rows, predicates);
      status = configureResult(bound, plan);
    }
    return status;
  }

  private StatusCode configureResult(BoundSqlStatement bound, SqlPhysicalPlan plan) {
    int count = bound.projectedColumnCount;
    StatusCode status = plan.beginResult(count);
    for (int projection = 0; status.isOk() && projection < count; projection++) {
      int raw = bound.projectionPrograms.rawColumn(projection);
      int scope = raw < 0 ? -1 : bound.projectionPrograms.scope(projection, 0);
      plan.setResultColumn(
          projection,
          bound.projectedColumns[projection],
          bound.projectedTypeDescriptors[projection],
          bound.command.columnOutputName(projection));
      plan.setResultNullable(
          projection,
          raw < 0 || context.table(scope).isNullable(raw)
              || scope > 0 && bound.command.joinChain().isLeft(scope - 1));
    }
    plan.setFilterCount(bound.command.wherePredicates().leafCount());
    if (status.isOk()) status = stages.describeUniversal(
        bound.command, context, source, bound.executableQuery.isAnalyze());
    if (status.isOk()) plan.setJoinStages(stages);
    return status;
  }

  StatusCode open() {
    if (!matched || active) return StatusCode.CONFLICT;
    StatusCode status = source.begin();
    if (status.isOk()) active = true;
    return status;
  }

  StatusCode next(SqlScanCursor cursor, SqlScanRowResult result) {
    if (!active) return StatusCode.CONFLICT;
    if (cursor.limitReached()) return StatusCode.CONFLICT;
    StatusCode status = source.next();
    if (status.isOk()) status = projections.projectUniversalJoin(rows, result);
    if (status.isOk()) cursor.rowReturned();
    return status;
  }

  StatusCode close() {
    StatusCode status = source.close();
    if (status.isOk()) status = rows.reset(context);
    if (status.isOk()) {
      predicates.reset();
      source.resetProgress();
      context = null;
      matched = false;
      active = false;
    }
    return status;
  }

  boolean matched() { return matched; }
  boolean active() { return active; }
}
