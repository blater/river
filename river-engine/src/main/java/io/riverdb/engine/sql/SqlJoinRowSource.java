package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.storage.heap.HeapRowResult;

/** Owns both physical JOIN cursors and emits one fully owned projected row. */
final class SqlJoinRowSource {
  private final BoundSqlStatement bound;
  private final SqlExpressionEvaluator expressions;
  private final SqlBoundPredicateEvaluator predicates;
  private final SqlJoinCursors cursors;
  private final SqlJoinOuterRow outer = new SqlJoinOuterRow();
  private boolean innerActive;
  private boolean matched;
  private long outerKey;
  private long matchValue;
  private long acceptedInnerKey;
  private HeapRowResult borrowedOuterRow;
  private HeapRowResult acceptedInnerRow;

  SqlJoinRowSource(
      RelationalSession relationalSession,
      BoundSqlStatement statement,
      SqlExpressionEvaluator evaluator,
      SqlBoundPredicateEvaluator predicateEvaluator) {
    bound = statement;
    expressions = evaluator;
    predicates = predicateEvaluator;
    cursors = new SqlJoinCursors(relationalSession, statement);
  }

  StatusCode begin() {
    resetProgress();
    return cursors.beginLeft();
  }

  StatusCode next() {
    if (innerActive) {
      StatusCode status = nextInner();
      if (status != StatusCode.CONFLICT) return status;
    }
    return nextOuter();
  }

  private StatusCode nextOuter() {
    while (true) {
      StatusCode status = captureNextOuter();
      if (!status.isOk()) return status;
      status = joinOuter();
      if (status != StatusCode.CONFLICT) return status;
    }
  }

  private StatusCode captureNextOuter() {
    StatusCode status = cursors.nextLeft();
    if (!status.isOk()) return status;
    outerKey = cursors.leftKey();
    HeapRowResult row = cursors.leftRow();
    outer.reset();
    borrowedOuterRow = row;
    return StatusCode.OK;
  }

  private StatusCode joinOuter() {
    matched = false;
    if (bound.joinOuterColumn < 0) return ownOuterAndBeginInner(0);
    if (expressions.isNull(outerRow(), bound.table, bound.joinOuterColumn)) {
      return unmatched();
    }
    matchValue = expressions.readColumn(
        outerKey, outerRow(), bound.joinOuterColumn);
    return cursors.rightUnique() ? joinUnique() : ownOuterAndBeginInner(matchValue);
  }

  private StatusCode ownOuterAndBeginInner(long value) {
    StatusCode status = outer.capture(borrowedOuterRow);
    if (!status.isOk()) return status;
    borrowedOuterRow = null;
    return beginInner(value);
  }

  private StatusCode beginInner(long value) {
    StatusCode status = cursors.beginRight(value);
    if (cursors.rightIndexed() && status == StatusCode.CONFLICT) return unmatched();
    if (!status.isOk()) return status;
    innerActive = true;
    return nextInner();
  }

  private StatusCode nextInner() {
    while (true) {
      StatusCode status = cursors.nextRight();
      if (status == StatusCode.CONFLICT) return completeInner();
      if (!status.isOk()) return status;
      HeapRowResult innerRow = cursors.rightRow();
      long innerKey = cursors.rightKey();
      if (!matchesAccess(innerKey, innerRow)) continue;
      boolean on = predicates.matchesJoinOn(
          outerKey, outerRow(), innerKey, innerRow);
      if (!predicates.joinStatus().isOk()) return predicates.joinStatus();
      if (!on) continue;
      matched = true;
      boolean where = predicates.matchesJoinWhere(
          outerKey, outerRow(), innerKey, innerRow);
      if (!predicates.joinStatus().isOk()) return predicates.joinStatus();
      if (where) return accept(innerKey, innerRow);
    }
  }

  private StatusCode completeInner() {
    StatusCode status = cursors.closeRight();
    if (!status.isOk()) return status;
    innerActive = false;
    return !matched ? unmatched() : StatusCode.CONFLICT;
  }

  private StatusCode joinUnique() {
    StatusCode status = cursors.fetchRight(matchValue);
    if (status == StatusCode.CONFLICT) return unmatched();
    if (!status.isOk()) return status;
    long innerKey = cursors.rightKey();
    HeapRowResult innerRow = cursors.rightRow();
    boolean on = predicates.matchesJoinOn(
        outerKey, outerRow(), innerKey, innerRow);
    if (!predicates.joinStatus().isOk()) return predicates.joinStatus();
    if (!on) return unmatched();
    matched = true;
    boolean where = predicates.matchesJoinWhere(
        outerKey, outerRow(), innerKey, innerRow);
    if (!predicates.joinStatus().isOk()) return predicates.joinStatus();
    return where ? accept(innerKey, innerRow) : StatusCode.CONFLICT;
  }

  private StatusCode unmatched() {
    if (!bound.command.isLeftJoin()) return StatusCode.CONFLICT;
    boolean where = predicates.matchesJoinWhere(
        outerKey, outerRow(), 0, null);
    if (!predicates.joinStatus().isOk()) return predicates.joinStatus();
    return where ? accept(0, null) : StatusCode.CONFLICT;
  }

  private StatusCode accept(long innerKey, HeapRowResult innerRow) {
    acceptedInnerKey = innerKey;
    acceptedInnerRow = innerRow;
    return StatusCode.OK;
  }

  private boolean matchesAccess(long innerKey, HeapRowResult innerRow) {
    if (bound.joinInnerColumn < 0 || cursors.rightIndexed()) return true;
    int innerColumn = bound.joinInnerColumn;
    return !expressions.isNull(innerRow, bound.joinTable, innerColumn)
        && expressions.compareExact(
            expressions.readColumn(innerKey, innerRow, innerColumn),
            bound.joinTable.typeDescriptor(innerColumn),
            matchValue,
            bound.table.typeDescriptor(bound.joinOuterColumn)) == 0;
  }

  boolean innerIndexed() { return cursors.rightIndexed(); }
  boolean innerUnique() { return cursors.rightUnique(); }
  boolean outerValueIndex() { return cursors.leftValueIndex(); }
  long outerKey() { return outerKey; }
  HeapRowResult outerRow() {
    return borrowedOuterRow != null ? borrowedOuterRow : outer.row();
  }
  long innerKey() { return acceptedInnerKey; }
  HeapRowResult innerRow() { return acceptedInnerRow; }

  boolean hasResources() {
    return cursors.hasResources();
  }

  StatusCode close() {
    StatusCode status = cursors.closeAll();
    outer.reset();
    resetProgress();
    return status;
  }

  private void resetProgress() {
    innerActive = false;
    matched = false;
    outerKey = 0;
    matchValue = 0;
    acceptedInnerKey = 0;
    borrowedOuterRow = null;
    acceptedInnerRow = null;
  }
}
