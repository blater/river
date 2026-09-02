package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.sql.SqlComparison;
import io.riverdb.sql.SqlCommand;
import io.riverdb.storage.heap.HeapRowResult;

/** Evaluates the predicates of the currently bound statement without allocating. */
final class SqlBoundPredicateEvaluator extends SqlJoinPredicateCallback {
  private final BoundSqlStatement bound;
  private final BoundSqlQuery query;
  private final SqlSubqueryGraphExecution subqueries;
  private final SqlBooleanPredicateEvaluator booleans;
  private final SqlJoinPredicateEvaluators joinOn;
  private final SqlTemporalContext temporal;
  private SqlCommand joinCommand;
  private SqlBoundJoinContext joinContext;
  private SqlBoundBooleanPredicateProgram joinWhere;
  private final SqlBooleanPredicateEvaluator.Match booleanMatch =
      new SqlBooleanPredicateEvaluator.Match();
  private boolean matched;
  private StatusCode joinStatus = StatusCode.OK;

  SqlBoundPredicateEvaluator(
      BoundSqlStatement statement,
      SqlExpressionEvaluator evaluator,
      SqlSubqueryGraphExecution graph,
      SqlTemporalContext temporal,
      SqlSessionShapeBudget shapeBudget) {
    bound = statement;
    query = statement.executableQuery;
    subqueries = graph;
    this.temporal = temporal;
    SqlBooleanPredicateWorkspace workspace =
        new SqlBooleanPredicateWorkspace(evaluator, temporal);
    joinOn = new SqlJoinPredicateEvaluators(workspace, temporal, shapeBudget);
    booleans = new SqlBooleanPredicateEvaluator(workspace, temporal, shapeBudget);
  }

  StatusCode prepare() {
    return booleans.prepare(bound.command, bound.whereBoolean);
  }

  StatusCode configureJoin(
      SqlCommand command,
      SqlBoundJoinContext context,
      SqlBoundBooleanPredicateProgram where) {
    joinCommand = command;
    joinContext = context;
    joinWhere = where;
    StatusCode status = StatusCode.OK;
    int stages = command.joinChain().stageCount();
    status = joinOn.prepare(stages);
    for (int stage = 0; status.isOk() && stage < stages; stage++) {
      status = context.hasOnBoolean(stage)
          ? joinOn.get(stage).prepare(command, context.onBoolean(stage))
          : StatusCode.OK;
    }
    return status.isOk()
        ? booleans.prepare(command, where) : status;
  }

  void reset() {
    booleans.reset();
    joinOn.reset();
    matched = false;
    joinStatus = StatusCode.OK;
    joinCommand = null;
    joinContext = null;
    joinWhere = null;
  }

  StatusCode evaluate(long primaryKey, HeapRowResult source) {
    matched = false;
    if (query.edgeCount() > 0) {
      StatusCode status = subqueries.matches(
          query.sourceBlockCount() - 1,
          primaryKey,
          source,
          booleanMatch);
      if (status.isOk()) matched = booleanMatch.matched();
      return status;
    }
    StatusCode status = booleans.matches(
          bound.command,
          bound.whereBoolean,
          primaryKey,
          source,
          bound.table,
          booleanMatch);
    if (status.isOk()) matched = booleanMatch.matched();
    return status;
  }

  StatusCode evaluateBlock(SqlBlockRow source) {
    matched = false;
    StatusCode status = booleans.matchesBlock(
        bound.command, bound.whereBoolean, source, booleanMatch);
    if (status.isOk()) matched = booleanMatch.matched();
    return status;
  }

  boolean matched() { return matched; }

  SqlBoundBooleanPredicateProgram program() { return bound.whereBoolean; }

  boolean matchesJoinOn(int stage, SqlJoinRoleRows rows) {
    joinStatus = joinOn.get(stage).matchesJoin(
        joinCommand,
        joinContext.onBoolean(stage),
        rows,
        booleanMatch);
    return joinStatus.isOk() && booleanMatch.matched();
  }

  HeapRowResult evaluatedRow(HeapRowResult original) {
    return subqueries.evaluatedRow(query.sourceBlockCount() - 1, original);
  }

  void releaseEvaluatedRow() {
    subqueries.releaseRow(query.sourceBlockCount() - 1);
  }

  boolean hasResources() {
    return subqueries.hasResources();
  }

  SqlSubqueryPlan subqueryPlan() { return subqueries.plan(); }
  SqlSubqueryResultCache subqueryCache() { return subqueries.resultCache(); }

  boolean matchesJoinWhere(SqlJoinRoleRows rows) {
    joinStatus = booleans.matchesJoin(
        joinCommand,
        joinWhere,
        rows,
        booleanMatch);
    return joinStatus.isOk() && booleanMatch.matched();
  }

  StatusCode joinStatus() { return joinStatus; }

}
