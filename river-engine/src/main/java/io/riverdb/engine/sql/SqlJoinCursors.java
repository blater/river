package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalScanCursor;
import io.riverdb.engine.relational.RelationalScanResult;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.relational.ValueIndexLookupResult;
import io.riverdb.storage.heap.HeapRowResult;

/** Owns the two relational cursors and their borrowed physical JOIN rows. */
final class SqlJoinCursors {
  private final RelationalSession session;
  private final BoundSqlStatement bound;
  private final RelationalScanCursor leftCursor = new RelationalScanCursor();
  private final RelationalScanCursor rightCursor = new RelationalScanCursor();
  private final RelationalScanResult leftScan = new RelationalScanResult();
  private final RelationalScanResult rightScan = new RelationalScanResult();
  private final ValueIndexLookupResult leftIndexed = new ValueIndexLookupResult();
  private final ValueIndexLookupResult rightIndexed = new ValueIndexLookupResult();
  private final HeapRowResult fetched = new HeapRowResult();
  private boolean leftValueIndex;
  private boolean rightIndexedAccess;
  private boolean rightUniqueAccess;
  private long leftKey;
  private long rightKey;
  private HeapRowResult leftRow;
  private HeapRowResult rightRow;

  SqlJoinCursors(RelationalSession relationalSession, BoundSqlStatement statement) {
    session = relationalSession;
    bound = statement;
  }

  StatusCode beginLeft() {
    configureRight();
    boolean predicate = bound.accessPredicate >= 0;
    boolean equality = predicate
        && bound.accessComparison == io.riverdb.sql.SqlComparison.EQUAL;
    leftValueIndex = predicate
        && bound.predicateColumn > 0
        && bound.table.hasIndexOn(bound.predicateColumn);
    boolean primary = predicate && bound.predicateColumn == 0;
    long lower = !predicate ? 0
        : equality ? bound.accessValue : bound.accessLowerInclusive;
    long upper = !predicate || equality ? 0 : bound.accessUpperExclusive;
    if (leftValueIndex) {
      return equality
          ? session.beginExactValueScan(
              bound.table, bound.predicateColumn, lower, leftCursor)
          : session.beginValueScan(
              bound.table, bound.predicateColumn, lower, upper, leftCursor);
    }
    return primary
        ? equality
            ? session.beginExactScan(bound.table, lower, leftCursor)
            : session.beginScan(bound.table, lower, upper, leftCursor)
        : session.beginScan(bound.table, leftCursor);
  }

  StatusCode nextLeft() {
    StatusCode status = leftValueIndex
        ? session.nextValueScan(bound.table, leftCursor, leftScan, leftIndexed)
        : session.nextScan(leftCursor, leftScan);
    if (!status.isOk()) return status;
    leftKey = leftValueIndex ? leftIndexed.key() : leftScan.key();
    leftRow = leftValueIndex ? leftIndexed.row() : leftScan.row();
    return validate(leftRow, bound.table.fixedRowBytes(), bound.table.maximumRowBytes());
  }

  StatusCode beginRight(long value) {
    return rightIndexedAccess
        ? session.beginNonUniqueValueLookup(
            bound.joinTable, bound.joinInnerColumn, value, rightCursor)
        : session.beginScan(bound.joinTable, rightCursor);
  }

  StatusCode nextRight() {
    StatusCode status = rightIndexedAccess
        ? session.nextNonUniqueValueLookup(
            bound.joinTable, rightCursor, rightIndexed)
        : session.nextScan(rightCursor, rightScan);
    if (!status.isOk()) return status;
    rightKey = rightIndexedAccess ? rightIndexed.key() : rightScan.key();
    rightRow = rightIndexedAccess ? rightIndexed.row() : rightScan.row();
    return validate(
        rightRow, bound.joinTable.fixedRowBytes(), bound.joinTable.maximumRowBytes());
  }

  StatusCode fetchRight(long value) {
    StatusCode status = bound.joinInnerColumn == 0
        ? session.fetch(bound.joinTable, value, fetched)
        : session.fetchByUniqueValue(
            bound.joinTable, bound.joinInnerColumn, value, rightIndexed);
    if (!status.isOk()) return status;
    rightKey = bound.joinInnerColumn == 0 ? value : rightIndexed.key();
    rightRow = bound.joinInnerColumn == 0 ? fetched : rightIndexed.row();
    return validate(
        rightRow, bound.joinTable.fixedRowBytes(), bound.joinTable.maximumRowBytes());
  }

  StatusCode closeRight() {
    StatusCode status = session.closeScan(rightCursor);
    if (status.isOk()) status = rightCursor.reset();
    if (status.isOk()) clearRight();
    return status;
  }

  StatusCode closeAll() {
    StatusCode status = rightCursor.isActive()
        ? session.closeScan(rightCursor) : StatusCode.OK;
    if (status.isOk() && leftCursor.isActive()) status = session.closeScan(leftCursor);
    if (status.isOk()) {
      status = rightCursor.reset();
      if (status.isOk()) status = leftCursor.reset();
    }
    if (status.isOk()) {
      clearRight();
      leftScan.reset();
      leftIndexed.reset();
      leftKey = 0;
      leftRow = null;
    }
    return status;
  }

  private void clearRight() {
    rightScan.reset();
    rightIndexed.reset();
    fetched.reset();
    rightKey = 0;
    rightRow = null;
  }

  private void configureRight() {
    int right = bound.joinInnerColumn;
    boolean access = bound.joinOuterColumn >= 0 && right >= 0;
    rightIndexedAccess = access && (right == 0 || bound.joinTable.hasIndexOn(right));
    rightUniqueAccess = access
        && (right == 0 || bound.joinTable.hasUniqueIndexOn(right));
  }

  boolean hasResources() { return leftCursor.isActive() || rightCursor.isActive(); }
  boolean leftValueIndex() { return leftValueIndex; }
  boolean rightIndexed() { return rightIndexedAccess; }
  boolean rightUnique() { return rightUniqueAccess; }
  long leftKey() { return leftKey; }
  HeapRowResult leftRow() { return leftRow; }
  long rightKey() { return rightKey; }
  HeapRowResult rightRow() { return rightRow; }

  private static StatusCode validate(
      HeapRowResult source, int fixedBytes, int maximumBytes) {
    return source.length() >= fixedBytes && source.length() <= maximumBytes
        ? StatusCode.OK : StatusCode.CORRUPTION;
  }
}
