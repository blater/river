package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.sql.SqlCommand;

/** Retained SQL-3VL ON and WHERE evaluators for universal join rows. */
final class SqlUniversalJoinPredicates {
  private final SqlBooleanPredicateWorkspace workspace;
  private final SqlBooleanPredicateEvaluator where;
  private final SqlJoinPredicateEvaluators on;
  private final SqlBooleanPredicateEvaluator.Match match =
      new SqlBooleanPredicateEvaluator.Match();
  private final SqlUniversalJoinedRowProvider nestedRows;
  private final SqlSubqueryLeafEvaluator subqueries;
  private final SqlSubqueryPlan plan;
  private final int block;
  private SqlCommand command;
  private SqlBoundJoinContext context;

  SqlUniversalJoinPredicates(
      SqlExpressionEvaluator expressions, SqlTemporalContext temporal) {
    this(
        expressions,
        temporal,
        -1,
        null,
        null,
        null,
        new SqlSessionShapeBudget(null));
  }

  SqlUniversalJoinPredicates(
      SqlExpressionEvaluator expressions,
      SqlTemporalContext temporal,
      SqlSessionShapeBudget shapeBudget) {
    this(expressions, temporal, -1, null, null, null, shapeBudget);
  }

  SqlUniversalJoinPredicates(
      SqlExpressionEvaluator expressions,
      SqlTemporalContext temporal,
      int queryBlock,
      SqlSubqueryLeafEvaluator leaves,
      SqlNestedRowProvider ancestors,
      SqlSubqueryPlan subqueryPlan,
      SqlSessionShapeBudget shapeBudget) {
    workspace = new SqlBooleanPredicateWorkspace(expressions, temporal);
    where = new SqlBooleanPredicateEvaluator(workspace, temporal, shapeBudget);
    on = new SqlJoinPredicateEvaluators(workspace, temporal, shapeBudget);
    block = queryBlock;
    subqueries = leaves;
    plan = subqueryPlan;
    nestedRows = queryBlock < 0 || leaves == null && ancestors == null ? null
        : new SqlUniversalJoinedRowProvider(queryBlock, ancestors);
  }

  StatusCode prepare(
      SqlCommand source,
      SqlBoundJoinContext joinContext,
      SqlBoundBooleanPredicateProgram whereProgram) {
    command = source;
    context = joinContext;
    int stages = source.joinChain().stageCount();
    StatusCode status = on.prepare(stages);
    for (int stage = 0; status.isOk() && stage < stages; stage++) {
      status = on.get(stage).prepare(source, joinContext.onBoolean(stage));
    }
    return status.isOk() ? where.prepare(source, whereProgram) : status;
  }

  StatusCode matchesOn(int stage, SqlUniversalJoinRows rows) {
    if (nestedRows == null) {
      return on.get(stage).matchesUniversalJoin(
          command, context.onBoolean(stage), rows, match);
    }
    nestedRows.activate(rows);
    StatusCode status = on.get(stage).matchesNested(
        command,
        context.onBoolean(stage),
        0,
        null,
        null,
        subqueries,
        nestedRows,
        match);
    nestedRows.clear();
    return status;
  }

  StatusCode matchesWhere(
      SqlBoundBooleanPredicateProgram program, SqlUniversalJoinRows rows) {
    if (nestedRows == null) {
      return where.matchesUniversalJoin(command, program, rows, match);
    }
    nestedRows.activate(rows);
    StatusCode status = where.matchesNested(
        command, program, 0, null, null, subqueries, nestedRows, match);
    nestedRows.clear();
    if (status.isOk() && match.matched() && plan != null) {
      plan.parentAccepted(block);
    }
    return status;
  }

  boolean matched() { return match.matched(); }

  void reset() {
    where.reset();
    on.reset();
    workspace.reset();
    command = null;
    context = null;
    if (nestedRows != null) nestedRows.clear();
  }
}
