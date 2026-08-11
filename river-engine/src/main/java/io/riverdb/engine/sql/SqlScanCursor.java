package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalScanCursor;
import io.riverdb.engine.relational.TableSchema;

/** Caller-owned capability for one ordered SQL table scan. */
public final class SqlScanCursor {
  private final RelationalScanCursor relational = new RelationalScanCursor();
  private final RelationalScanCursor joinInnerRelational = new RelationalScanCursor();
  private final long[] joinOuterProjectedValues =
      new long[TableSchema.MAXIMUM_COLUMNS];
  private SqlSession owner;
  private boolean aggregate;
  private boolean groupCount;
  private boolean distinct;
  private boolean join;
  private boolean joinInnerScanActive;
  private boolean joinInnerUnique;
  private boolean groupLookahead;
  private boolean groupInputExhausted;
  private boolean distinctValueAvailable;
  private boolean aggregateTransactionActive;
  private long aggregateValue;
  private long aggregateCommitSequence;
  private long groupLookaheadValue;
  private long distinctValue;
  private int groupColumn = -1;
  private int joinOuterColumn = -1;
  private int joinInnerColumn = -1;
  private long joinOuterKey;
  private boolean implicitTransaction;
  private boolean valueIndex;
  private long maximumRows = Long.MAX_VALUE;
  private final int[] projectedColumns = new int[TableSchema.MAXIMUM_COLUMNS];
  private int projectedColumnCount;
  private boolean active;
  private long rowsReturned;

  public StatusCode reset() {
    if (active) {
      return StatusCode.CONFLICT;
    }
    owner = null;
    aggregate = false;
    groupCount = false;
    distinct = false;
    join = false;
    joinInnerScanActive = false;
    joinInnerUnique = false;
    groupLookahead = false;
    groupInputExhausted = false;
    distinctValueAvailable = false;
    aggregateTransactionActive = false;
    aggregateValue = 0;
    aggregateCommitSequence = 0;
    groupLookaheadValue = 0;
    distinctValue = 0;
    groupColumn = -1;
    joinOuterColumn = -1;
    joinInnerColumn = -1;
    joinOuterKey = 0;
    implicitTransaction = false;
    valueIndex = false;
    maximumRows = Long.MAX_VALUE;
    projectedColumnCount = 0;
    rowsReturned = 0;
    StatusCode status = relational.reset();
    StatusCode inner = joinInnerRelational.reset();
    return status.isOk() ? inner : status;
  }

  RelationalScanCursor relational() {
    return relational;
  }

  RelationalScanCursor joinInnerRelational() {
    return joinInnerRelational;
  }

  StatusCode claim(
      SqlSession session,
      boolean implicit,
      boolean indexedValue,
      int[] projections,
      int projectionCount,
      long rowLimit) {
    if (active
        || projections == null
        || projectionCount <= 0
        || projectionCount > projectedColumns.length
        || rowLimit < 0) {
      return StatusCode.CONFLICT;
    }
    owner = session;
    implicitTransaction = implicit;
    valueIndex = indexedValue;
    maximumRows = rowLimit;
    projectedColumnCount = projectionCount;
    for (int index = 0; index < projectionCount; index++) {
      projectedColumns[index] = projections[index];
    }
    active = true;
    rowsReturned = 0;
    return StatusCode.OK;
  }

  StatusCode claimAggregate(
      SqlSession session,
      long value,
      boolean transactionActive,
      long commitSequence) {
    if (active || session == null || commitSequence < 0) {
      return StatusCode.CONFLICT;
    }
    owner = session;
    aggregate = true;
    aggregateValue = value;
    aggregateTransactionActive = transactionActive;
    aggregateCommitSequence = commitSequence;
    projectedColumnCount = 1;
    active = true;
    rowsReturned = 0;
    return StatusCode.OK;
  }

  StatusCode claimGroupCount(
      SqlSession session,
      boolean implicit,
      int column,
      boolean indexedValue,
      long rowLimit) {
    if (active || session == null || column < 0 || rowLimit < 0) {
      return StatusCode.CONFLICT;
    }
    owner = session;
    implicitTransaction = implicit;
    valueIndex = indexedValue;
    groupCount = true;
    groupColumn = column;
    maximumRows = rowLimit;
    projectedColumns[0] = column;
    projectedColumnCount = 2;
    active = true;
    rowsReturned = 0;
    return StatusCode.OK;
  }

  StatusCode claimDistinct(
      SqlSession session,
      boolean implicit,
      int column,
      boolean indexedValue,
      long rowLimit) {
    if (active || session == null || column < 0 || rowLimit < 0) {
      return StatusCode.CONFLICT;
    }
    owner = session;
    implicitTransaction = implicit;
    valueIndex = indexedValue;
    distinct = true;
    groupColumn = column;
    maximumRows = rowLimit;
    projectedColumns[0] = column;
    projectedColumnCount = 1;
    active = true;
    rowsReturned = 0;
    return StatusCode.OK;
  }

  StatusCode claimJoin(
      SqlSession session,
      boolean implicit,
      int outerColumn,
      int innerColumn,
      boolean indexedOuter,
      boolean uniqueInner,
      int[] projections,
      int projectionCount,
      long rowLimit) {
    if (active
        || session == null
        || outerColumn < 0
        || innerColumn < 0
        || projections == null
        || projectionCount <= 0
        || projectionCount > projectedColumns.length
        || rowLimit < 0) {
      return StatusCode.CONFLICT;
    }
    owner = session;
    implicitTransaction = implicit;
    join = true;
    valueIndex = indexedOuter;
    joinOuterColumn = outerColumn;
    joinInnerColumn = innerColumn;
    joinInnerUnique = uniqueInner;
    maximumRows = rowLimit;
    projectedColumnCount = projectionCount;
    for (int index = 0; index < projectionCount; index++) {
      projectedColumns[index] = projections[index];
    }
    active = true;
    rowsReturned = 0;
    return StatusCode.OK;
  }

  boolean isOwnedBy(SqlSession session) {
    return active && owner == session;
  }

  boolean implicitTransaction() {
    return implicitTransaction;
  }

  boolean valueIndex() {
    return valueIndex;
  }

  boolean aggregate() {
    return aggregate;
  }

  boolean groupCount() {
    return groupCount;
  }

  boolean distinct() {
    return distinct;
  }

  boolean join() {
    return join;
  }

  int joinOuterColumn() {
    return joinOuterColumn;
  }

  int joinInnerColumn() {
    return joinInnerColumn;
  }

  boolean joinInnerUnique() {
    return joinInnerUnique;
  }

  boolean joinInnerScanActive() {
    return joinInnerScanActive;
  }

  void beginJoinInnerScan(long outerKey) {
    joinOuterKey = outerKey;
    joinInnerScanActive = true;
  }

  void completeJoinInnerScan() {
    joinInnerScanActive = false;
  }

  long joinOuterKey() {
    return joinOuterKey;
  }

  void setJoinOuterProjectedValue(int index, long value) {
    joinOuterProjectedValues[index] = value;
  }

  long joinOuterProjectedValue(int index) {
    return joinOuterProjectedValues[index];
  }

  int groupColumn() {
    return groupColumn;
  }

  boolean groupInputExhausted() {
    return groupInputExhausted;
  }

  void exhaustGroupInput() {
    groupInputExhausted = true;
  }

  boolean hasGroupLookahead() {
    return groupLookahead;
  }

  long takeGroupLookahead() {
    groupLookahead = false;
    return groupLookaheadValue;
  }

  void setGroupLookahead(long value) {
    groupLookaheadValue = value;
    groupLookahead = true;
  }

  boolean hasDistinctValue() {
    return distinctValueAvailable;
  }

  long distinctValue() {
    return distinctValue;
  }

  void setDistinctValue(long value) {
    distinctValue = value;
    distinctValueAvailable = true;
  }

  long aggregateValue() {
    return aggregateValue;
  }

  boolean aggregateTransactionActive() {
    return aggregateTransactionActive;
  }

  long aggregateCommitSequence() {
    return aggregateCommitSequence;
  }

  boolean limitReached() {
    return rowsReturned >= maximumRows;
  }

  public int projectedColumn(int index) {
    return index >= 0 && index < projectedColumnCount ? projectedColumns[index] : -1;
  }

  public int projectedColumnCount() {
    return projectedColumnCount;
  }

  void complete() {
    active = false;
  }

  void rowReturned() {
    rowsReturned++;
  }

  public long rowsReturned() {
    return rowsReturned;
  }

  public boolean isActive() {
    return active;
  }
}
