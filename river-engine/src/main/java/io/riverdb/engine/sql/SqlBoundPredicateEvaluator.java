package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.sql.SqlComparison;
import io.riverdb.sql.SqlJoinChain;
import io.riverdb.storage.heap.HeapRowResult;

/** Evaluates the predicates of the currently bound statement without allocating. */
final class SqlBoundPredicateEvaluator {
  private final BoundSqlStatement bound;
  private final BoundSqlQuery query;
  private final SqlSubqueryGraphExecution subqueries;
  private final SqlBooleanPredicateEvaluator booleans;
  private final SqlBooleanPredicateEvaluator[] joinOn =
      new SqlBooleanPredicateEvaluator[SqlJoinChain.MAXIMUM_JOIN_STAGES];
  private final SqlBooleanPredicateWorkspace joinWorkspace;
  private final SqlTemporalContext temporal;
  private final SqlBooleanPredicateEvaluator.Match booleanMatch =
      new SqlBooleanPredicateEvaluator.Match();
  private boolean matched;
  private StatusCode joinStatus = StatusCode.OK;

  SqlBoundPredicateEvaluator(
      BoundSqlStatement statement,
      SqlExpressionEvaluator evaluator,
      SqlSubqueryGraphExecution graph,
      SqlTemporalContext temporal) {
    bound = statement;
    query = statement.executableQuery;
    subqueries = graph;
    this.temporal = temporal;
    SqlBooleanPredicateWorkspace workspace =
        new SqlBooleanPredicateWorkspace(evaluator, temporal);
    joinWorkspace = workspace;
    booleans = new SqlBooleanPredicateEvaluator(workspace, temporal);
  }

  StatusCode prepare() {
    StatusCode status = StatusCode.OK;
    int stages = bound.command.joinChain() == null
        ? 0 : bound.command.joinChain().stageCount();
    for (int stage = 0; status.isOk() && stage < stages; stage++) {
      if (joinOn[stage] == null) {
        joinOn[stage] = new SqlBooleanPredicateEvaluator(joinWorkspace, temporal);
      }
      status = bound.hasOnBoolean(stage)
          ? joinOn[stage].prepare(bound.command, bound.onBoolean(stage))
          : StatusCode.OK;
    }
    return status.isOk()
        ? booleans.prepare(bound.command, bound.whereBoolean) : status;
  }

  void reset() {
    booleans.reset();
    for (SqlBooleanPredicateEvaluator evaluator : joinOn) {
      if (evaluator != null) evaluator.reset();
    }
    matched = false;
    joinStatus = StatusCode.OK;
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

  boolean matched() { return matched; }

  boolean matchesJoinOn(int stage, SqlJoinRoleRows rows) {
    joinStatus = joinOn[stage].matchesJoin(
        bound.command,
        bound.onBoolean(stage),
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

  boolean matchesJoinWhere(SqlJoinRoleRows rows) {
    joinStatus = booleans.matchesJoin(
        bound.command,
        bound.whereBoolean,
        rows,
        booleanMatch);
    return joinStatus.isOk() && booleanMatch.matched();
  }

  StatusCode joinStatus() { return joinStatus; }

}
