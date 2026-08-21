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
  private final SqlExpressionEvaluator expressions;
  private final SqlNestedQueryExecution nested;
  private final SqlNestedPredicateEvaluator nestedPredicates;
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
      SqlNestedQueryExecution nestedExecution,
      SqlTemporalContext temporal) {
    bound = statement;
    query = statement.executableQuery;
    expressions = evaluator;
    nested = nestedExecution;
    nestedPredicates = new SqlNestedPredicateEvaluator(evaluator);
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
    nestedPredicates.reset();
    matched = false;
    joinStatus = StatusCode.OK;
  }

  StatusCode evaluate(long primaryKey, HeapRowResult source) {
    matched = false;
    if (nested.rejectsOuterRow()) return StatusCode.OK;
    if (bound.whereBoolean.available() && !bound.query.hasNestedTopology()) {
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
    BoundSqlQuery.Block command = query.root();
    nestedPredicates.reset();
    for (int index = 0; index < bound.predicateCount; index++) {
      boolean predicateMatched = matches(
          primaryKey, source, command.predicates(), index);
      StatusCode status = nestedPredicates.status();
      if (!status.isOk()) return status;
      if (!predicateMatched) return StatusCode.OK;
    }
    matched = true;
    return StatusCode.OK;
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

  boolean matchesJoinWhere(SqlJoinRoleRows rows) {
    joinStatus = booleans.matchesJoin(
        bound.command,
        bound.whereBoolean,
        rows,
        booleanMatch);
    return joinStatus.isOk() && booleanMatch.matched();
  }

  StatusCode joinStatus() { return joinStatus; }

  private boolean matches(
      long primaryKey,
      HeapRowResult source,
      SqlNestedPredicatePlan command,
      int index) {
    int column = command.resolvedColumn(index);
    boolean nullValue = expressions.isNull(source, bound.table, column);
    if (command.isTruth(index)) {
      long value = nullValue ? 0 : expressions.readColumn(primaryKey, source, column);
      return nestedPredicates.matchesTruth(command, index, nullValue, value);
    }
    if (command.isNullTest(index)) {
      return nullValue != command.isNullTestNegated(index);
    }
    if (nullValue) {
      return false;
    }
    long value = expressions.readColumn(primaryKey, source, column);
    if (query.hasMembershipPredicate() && query.membershipPredicate() == index) {
      return nested.matchesMembership(
          value, bound.table.typeDescriptor(column), source, column);
    }
    if (query.hasScalarPredicate() && query.scalarPredicate() == index) {
      return nested.matchesScalar(value, bound.table.typeDescriptor(column));
    }
    return nestedPredicates.matchesLiteral(
        primaryKey, source, bound.table, column, command, index);
  }
}
