package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.sql.SqlCommand;

/** Block-owned JOIN predicate state safe across recursive child evaluation. */
final class SqlJoinedPredicateEvaluator extends SqlJoinPredicateCallback {
  private final SqlBooleanPredicateWorkspace workspace;
  private final SqlBooleanPredicateEvaluator where;
  private final SqlJoinPredicateEvaluators on;
  private final SqlBooleanPredicateEvaluator.Match match =
      new SqlBooleanPredicateEvaluator.Match();
  private final SqlJoinedRowProvider rows;
  private final SqlTemporalContext temporal;
  private final SqlSubqueryLeafEvaluator subqueries;
  private final SqlSubqueryPlan plan;
  private final int block;
  private SqlCommand command;
  private SqlBoundJoinContext context;
  private SqlBoundBooleanPredicateProgram whereProgram;
  private StatusCode status = StatusCode.OK;

  SqlJoinedPredicateEvaluator(
      int block,
      SqlExpressionEvaluator expressions,
      SqlTemporalContext temporalContext,
      SqlSubqueryLeafEvaluator leafEvaluator,
      SqlNestedRowProvider ancestors,
      SqlSubqueryPlan subqueryPlan,
      SqlSessionShapeBudget shapeBudget) {
    this.block = block;
    temporal = temporalContext;
    subqueries = leafEvaluator;
    plan = subqueryPlan;
    workspace = new SqlBooleanPredicateWorkspace(expressions, temporalContext);
    where = new SqlBooleanPredicateEvaluator(workspace, temporalContext, shapeBudget);
    on = new SqlJoinPredicateEvaluators(workspace, temporalContext, shapeBudget);
    rows = new SqlJoinedRowProvider(block, ancestors);
  }

  @Override
  StatusCode configureJoin(
      SqlCommand canonicalCommand,
      SqlBoundJoinContext joinContext,
      SqlBoundBooleanPredicateProgram boundWhere) {
    if (canonicalCommand == null || canonicalCommand.joinChain() == null
        || joinContext == null || boundWhere == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    command = canonicalCommand;
    context = joinContext;
    whereProgram = boundWhere;
    status = on.prepare(command.joinChain().stageCount());
    for (int stage = 0;
        status.isOk() && stage < command.joinChain().stageCount(); stage++) {
      status = on.get(stage).prepare(command, context.onBoolean(stage));
    }
    if (status.isOk()) status = where.prepare(command, whereProgram);
    return status;
  }

  @Override
  boolean matchesJoinOn(int stage, SqlJoinRoleRows localRows) {
    rows.activate(localRows);
    status = on.get(stage).matchesNested(
        command,
        context.onBoolean(stage),
        0,
        null,
        null,
        subqueries,
        rows,
        match);
    rows.clear();
    return status.isOk() && match.matched();
  }

  @Override
  boolean matchesJoinWhere(SqlJoinRoleRows localRows) {
    rows.activate(localRows);
    status = where.matchesNested(
        command,
        whereProgram,
        0,
        null,
        null,
        subqueries,
        rows,
        match);
    rows.clear();
    boolean accepted = status.isOk() && match.matched();
    if (accepted) plan.parentAccepted(block);
    return accepted;
  }

  @Override
  StatusCode joinStatus() { return status; }

  void reset() {
    where.reset();
    on.reset();
    rows.clear();
    command = null;
    context = null;
    whereProgram = null;
    status = StatusCode.OK;
  }
}
