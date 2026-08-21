package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.sql.SqlJoinChain;
import io.riverdb.storage.heap.HeapRowResult;

/** Resumable left-deep executor for one bounded canonical JOIN chain. */
final class SqlJoinChainSource {
  private final BoundSqlStatement bound;
  private final SqlExpressionEvaluator expressions;
  private final SqlBoundPredicateEvaluator predicates;
  private final SqlJoinChainCursors cursors;
  private final SqlJoinRoleRows rows;
  private final boolean[] opened =
      new boolean[SqlJoinChain.MAXIMUM_JOIN_STAGES];
  private final boolean[] matched =
      new boolean[SqlJoinChain.MAXIMUM_JOIN_STAGES];
  private final boolean[] finished =
      new boolean[SqlJoinChain.MAXIMUM_JOIN_STAGES];
  private final boolean[] nullEmitted =
      new boolean[SqlJoinChain.MAXIMUM_JOIN_STAGES];
  private final boolean[] uniqueTried =
      new boolean[SqlJoinChain.MAXIMUM_JOIN_STAGES];
  private final long[] candidates =
      new long[SqlJoinChain.MAXIMUM_JOIN_STAGES];
  private final long[] onTrue =
      new long[SqlJoinChain.MAXIMUM_JOIN_STAGES];
  private final long[] nullExtensions =
      new long[SqlJoinChain.MAXIMUM_JOIN_STAGES];
  private long rootCandidates;
  private long whereTrue;
  private int stage = -1;
  private boolean beginAccepted;

  SqlJoinChainSource(
      io.riverdb.engine.relational.RelationalSession session,
      BoundSqlStatement statement,
      SqlExpressionEvaluator evaluator,
      SqlBoundPredicateEvaluator predicateEvaluator) {
    bound = statement;
    expressions = evaluator;
    predicates = predicateEvaluator;
    cursors = new SqlJoinChainCursors(session, statement);
    rows = new SqlJoinRoleRows(statement);
  }

  StatusCode begin() {
    resetProgress();
    resetMetrics();
    return cursors.beginRoot();
  }

  void resetMetrics() { resetCounters(); }

  StatusCode next() {
    while (true) {
      if (stage < 0) {
        StatusCode status = nextRoot();
        if (!status.isOk()) return status;
      }
      StatusCode status = nextStage();
      if (status != StatusCode.CONFLICT) return status;
    }
  }

  private StatusCode nextRoot() {
    StatusCode status = cursors.nextRoot();
    if (!status.isOk()) return status;
    rootCandidates++;
    rows.clearFrom(0);
    rows.borrow(0, cursors.key(0), cursors.row(0));
    resetStages();
    stage = 0;
    return StatusCode.OK;
  }

  private StatusCode nextStage() {
    if (finished[stage]) return finishStage();
    if (!opened[stage] && !uniqueTried[stage]) {
      StatusCode status = beginStage();
      if (!status.isOk()) return status;
      if (beginAccepted) {
        beginAccepted = false;
        return StatusCode.OK;
      }
      if (finished[stage]) return finishStage();
    }
    if (uniqueTried[stage]) {
      finished[stage] = true;
      return finishStage();
    }
    return nextScannedCandidate();
  }

  private StatusCode beginStage() {
    int rightRole = stage + 1;
    int outerRole = bound.joinAccessOuterRole(stage);
    int outerColumn = bound.joinAccessOuterColumn(stage);
    if (outerColumn >= 0) {
      HeapRowResult outerRow = rows.row(outerRole);
      if (outerRow == null
          || expressions.isNull(
              outerRow, bound.joinRole(outerRole), outerColumn)) {
        finished[stage] = true;
        return StatusCode.OK;
      }
      long value = expressions.readColumn(
          rows.key(outerRole), outerRow, outerColumn);
      StatusCode status = rows.ownThrough(stage);
      if (!status.isOk()) return status;
      if (cursors.rightUnique(stage)) return uniqueRole(rightRole, value);
      status = cursors.beginRole(rightRole, value);
      if (status == StatusCode.CONFLICT && cursors.rightIndexed(stage)) {
        finished[stage] = true;
        return StatusCode.OK;
      }
      if (!status.isOk()) return status;
      opened[stage] = true;
      return StatusCode.OK;
    }
    StatusCode status = rows.ownThrough(stage);
    if (!status.isOk()) return status;
    status = cursors.beginRole(rightRole, 0);
    if (!status.isOk()) return status;
    opened[stage] = true;
    return StatusCode.OK;
  }

  private StatusCode uniqueRole(int role, long value) {
    uniqueTried[stage] = true;
    StatusCode status = cursors.fetchRole(role, value);
    if (status == StatusCode.CONFLICT) {
      finished[stage] = true;
      return StatusCode.OK;
    }
    if (!status.isOk()) return status;
    candidates[stage]++;
    rows.borrow(role, cursors.key(role), cursors.row(role));
    status = acceptCandidate();
    if (status.isOk()) beginAccepted = true;
    return status;
  }

  private StatusCode nextScannedCandidate() {
    int rightRole = stage + 1;
    StatusCode status = cursors.nextRole(rightRole);
    if (status == StatusCode.CONFLICT) {
      status = cursors.closeRole(rightRole);
      if (!status.isOk()) return status;
      opened[stage] = false;
      finished[stage] = true;
      return finishStage();
    }
    if (!status.isOk()) return status;
    candidates[stage]++;
    rows.borrow(rightRole, cursors.key(rightRole), cursors.row(rightRole));
    if (!matchesFirstAccess()) return StatusCode.CONFLICT;
    return acceptCandidate();
  }

  private StatusCode acceptCandidate() {
    if (!predicates.matchesJoinOn(stage, rows)) {
      return predicates.joinStatus().isOk()
          ? StatusCode.CONFLICT : predicates.joinStatus();
    }
    matched[stage] = true;
    onTrue[stage]++;
    if (stage + 1 == bound.command.joinChain().stageCount()) {
      boolean where = predicates.matchesJoinWhere(rows);
      if (!predicates.joinStatus().isOk()) return predicates.joinStatus();
      if (where) whereTrue++;
      return where ? StatusCode.OK : StatusCode.CONFLICT;
    }
    StatusCode status = rows.ownThrough(stage + 1);
    if (!status.isOk()) return status;
    stage++;
    resetStage(stage);
    return StatusCode.CONFLICT;
  }

  private StatusCode finishStage() {
    SqlJoinChain joins = bound.command.joinChain();
    int rightRole = stage + 1;
    if (!matched[stage] && joins.isLeft(stage) && !nullEmitted[stage]) {
      nullEmitted[stage] = true;
      nullExtensions[stage]++;
      rows.setNull(rightRole);
      if (stage + 1 == joins.stageCount()) {
        boolean where = predicates.matchesJoinWhere(rows);
        if (!predicates.joinStatus().isOk()) return predicates.joinStatus();
        if (where) whereTrue++;
        return where ? StatusCode.OK : StatusCode.CONFLICT;
      }
      stage++;
      resetStage(stage);
      return StatusCode.CONFLICT;
    }
    rows.clearFrom(rightRole);
    resetStage(stage);
    stage--;
    return StatusCode.CONFLICT;
  }

  private boolean matchesFirstAccess() {
    int innerColumn = bound.joinAccessInnerColumn(stage);
    if (innerColumn < 0 || cursors.rightIndexed(stage)) return true;
    int rightRole = stage + 1;
    HeapRowResult right = rows.row(rightRole);
    TableDefinition rightTable = bound.joinRole(rightRole);
    if (expressions.isNull(right, rightTable, innerColumn)) {
      return false;
    }
    int outerRole = bound.joinAccessOuterRole(stage);
    int outerColumn = bound.joinAccessOuterColumn(stage);
    long left = expressions.readColumn(
        rows.key(outerRole), rows.row(outerRole), outerColumn);
    long value = expressions.readColumn(
        rows.key(rightRole), right, innerColumn);
    return expressions.compareExact(
        value,
        rightTable.typeDescriptor(innerColumn),
        left,
        bound.joinRole(outerRole).typeDescriptor(outerColumn)) == 0;
  }

  private void resetStages() {
    for (int current = 0; current < opened.length; current++) resetStage(current);
  }

  private void resetStage(int current) {
    opened[current] = false;
    matched[current] = false;
    finished[current] = false;
    nullEmitted[current] = false;
    uniqueTried[current] = false;
  }

  SqlJoinRoleRows rows() { return rows; }
  boolean innerIndexed() { return cursors.rightIndexed(0); }
  boolean innerUnique() { return cursors.rightUnique(0); }
  boolean outerValueIndex() { return cursors.rootValueIndex(); }
  long rootCandidates() { return rootCandidates; }
  long stageCandidates(int current) { return candidates[current]; }
  long stageOnTrue(int current) { return onTrue[current]; }
  long stageNullExtensions(int current) { return nullExtensions[current]; }
  long stagePublished(int current) {
    return onTrue[current] + nullExtensions[current];
  }
  long whereTrue() { return whereTrue; }
  boolean hasResources() { return cursors.hasResources(); }

  StatusCode close() {
    StatusCode status = cursors.closeAll();
    if (!status.isOk()) return status;
    rows.reset();
    resetProgress();
    return StatusCode.OK;
  }

  private void resetProgress() {
    stage = -1;
    beginAccepted = false;
    resetStages();
  }

  private void resetCounters() {
    rootCandidates = 0;
    whereTrue = 0;
    for (int current = 0; current < candidates.length; current++) {
      candidates[current] = 0;
      onTrue[current] = 0;
      nullExtensions[current] = 0;
    }
  }
}
