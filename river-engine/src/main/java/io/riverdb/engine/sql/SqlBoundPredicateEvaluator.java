package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.storage.heap.HeapRowResult;

/** Evaluates the predicates of the currently bound statement without allocating. */
final class SqlBoundPredicateEvaluator {
  private final BoundSqlStatement bound;
  private final BoundSqlQuery query;
  private final SqlSubqueryGraphExecution subqueries;
  private final SqlBooleanPredicateEvaluator booleans;
  private final SqlBooleanPredicateEvaluator joinOn;
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
    SqlBooleanPredicateWorkspace workspace =
        new SqlBooleanPredicateWorkspace(evaluator, temporal);
    booleans = new SqlBooleanPredicateEvaluator(workspace, temporal);
    joinOn = new SqlBooleanPredicateEvaluator(workspace, temporal);
  }

  StatusCode prepare() {
    StatusCode status = bound.hasOnBoolean()
        ? joinOn.prepare(bound.command, bound.onBoolean()) : StatusCode.OK;
    return status.isOk()
        ? booleans.prepare(bound.command, bound.whereBoolean) : status;
  }

  void reset() {
    booleans.reset();
    joinOn.reset();
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

  HeapRowResult evaluatedRow(HeapRowResult original) {
    return subqueries.evaluatedRow(query.sourceBlockCount() - 1, original);
  }

  void releaseEvaluatedRow() {
    subqueries.releaseRow(query.sourceBlockCount() - 1);
  }

  boolean matchesJoinOn(
      long outerKey,
      HeapRowResult outerRow,
      long innerKey,
      HeapRowResult innerRow) {
    joinStatus = joinOn.matchesJoin(
        bound.command,
        bound.onBoolean(),
        outerKey,
        outerRow,
        bound.table,
        innerKey,
        innerRow,
        bound.joinTable,
        booleanMatch);
    return joinStatus.isOk() && booleanMatch.matched();
  }

  boolean matchesJoinWhere(
      long outerKey,
      HeapRowResult outerRow,
      long innerKey,
      HeapRowResult innerRow) {
    joinStatus = booleans.matchesJoin(
        bound.command,
        bound.whereBoolean,
        outerKey,
        outerRow,
        bound.table,
        innerKey,
        innerRow,
        bound.joinTable,
        booleanMatch);
    return joinStatus.isOk() && booleanMatch.matched();
  }

  StatusCode joinStatus() { return joinStatus; }

}
