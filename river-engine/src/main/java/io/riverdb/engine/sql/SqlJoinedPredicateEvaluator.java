package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlJoinChain;

/** Block-owned JOIN predicate state safe across recursive child evaluation. */
final class SqlJoinedPredicateEvaluator extends SqlJoinPredicateCallback {
  private final SqlBooleanPredicateWorkspace workspace;
  private final SqlBooleanPredicateEvaluator where;
  private final SqlBooleanPredicateEvaluator[] on =
      new SqlBooleanPredicateEvaluator[SqlJoinChain.MAXIMUM_JOIN_STAGES];
  private final SqlBooleanPredicateEvaluator.Match match =
      new SqlBooleanPredicateEvaluator.Match();
  private final SqlJoinedRowProvider rows;
  private final SqlTemporalContext temporal;
  private final SqlSubqueryLeafEvaluator subqueries;
  private SqlCommand command;
  private SqlBoundJoinContext context;
  private SqlBoundBooleanPredicateProgram whereProgram;
  private StatusCode status = StatusCode.OK;

  SqlJoinedPredicateEvaluator(
      int block,
      SqlExpressionEvaluator expressions,
      SqlTemporalContext temporalContext,
      SqlSubqueryLeafEvaluator leafEvaluator,
      SqlNestedRowProvider ancestors) {
    temporal = temporalContext;
    subqueries = leafEvaluator;
    workspace = new SqlBooleanPredicateWorkspace(expressions, temporalContext);
    where = new SqlBooleanPredicateEvaluator(workspace, temporalContext);
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
    status = StatusCode.OK;
    for (int stage = 0;
        status.isOk() && stage < command.joinChain().stageCount(); stage++) {
      if (on[stage] == null) {
        on[stage] = new SqlBooleanPredicateEvaluator(workspace, temporal);
      }
      status = on[stage].prepare(command, context.onBoolean(stage));
    }
    if (status.isOk()) status = where.prepare(command, whereProgram);
    return status;
  }

  @Override
  boolean matchesJoinOn(int stage, SqlJoinRoleRows localRows) {
    rows.activate(localRows);
    status = on[stage].matchesNested(
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
    return status.isOk() && match.matched();
  }

  @Override
  StatusCode joinStatus() { return status; }

  void reset() {
    where.reset();
    for (SqlBooleanPredicateEvaluator evaluator : on) {
      if (evaluator != null) evaluator.reset();
    }
    rows.clear();
    command = null;
    context = null;
    whereProgram = null;
    status = StatusCode.OK;
  }
}
