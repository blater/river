package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.CatalogObjectCursor;
import io.riverdb.engine.relational.CatalogIndexCursor;
import io.riverdb.engine.relational.RelationalScanCursor;
import io.riverdb.engine.relational.TableSchema;

/** Reusable physical operator progress for the one active query in a SQL session. */
final class SqlActiveScanState {
  private final RelationalScanCursor relational = new RelationalScanCursor();
  private final CatalogObjectCursor catalogObjects = new CatalogObjectCursor();
  private final CatalogIndexCursor catalogIndexes = new CatalogIndexCursor();
  private final RelationalScanCursor joinInnerRelational = new RelationalScanCursor();
  private final long[] joinOuterProjectedValues =
      new long[TableSchema.MAXIMUM_COLUMNS];
  private boolean joinInnerScanActive;
  private boolean joinMatched;
  private boolean groupLookahead;
  private boolean groupLookaheadNull;
  private boolean groupInputExhausted;
  private boolean distinctValueAvailable;
  private boolean distinctValueNull;
  private boolean aggregateTransactionActive;
  private boolean aggregateNull;
  private long aggregateValue;
  private long aggregateCommitSequence;
  private long explainCommitSequence;
  private long groupLookaheadValue;
  private long groupLookaheadAggregateValue;
  private boolean groupLookaheadAggregateNull;
  private long distinctValue;
  private long joinOuterKey;
  private long joinMatchValue;
  private long joinOuterNullMask;
  private int sortedRowCount;
  private int sortedRowIndex;
  private int planStepIndex;
  private boolean active;

  public StatusCode reset() {
    if (active) {
      return StatusCode.CONFLICT;
    }
    joinInnerScanActive = false;
    joinMatched = false;
    groupLookahead = false;
    groupLookaheadNull = false;
    groupInputExhausted = false;
    distinctValueAvailable = false;
    distinctValueNull = false;
    aggregateTransactionActive = false;
    aggregateNull = false;
    aggregateValue = 0;
    aggregateCommitSequence = 0;
    explainCommitSequence = 0;
    groupLookaheadValue = 0;
    groupLookaheadAggregateValue = 0;
    groupLookaheadAggregateNull = false;
    distinctValue = 0;
    joinOuterKey = 0;
    joinMatchValue = 0;
    joinOuterNullMask = 0;
    sortedRowCount = 0;
    sortedRowIndex = 0;
    planStepIndex = 0;
    StatusCode status = relational.reset();
    StatusCode inner = joinInnerRelational.reset();
    StatusCode catalog = catalogObjects.reset();
    StatusCode indexes = catalogIndexes.reset();
    return !status.isOk()
        ? status : !inner.isOk() ? inner : !catalog.isOk() ? catalog : indexes;
  }

  CatalogObjectCursor catalogObjects() {
    return catalogObjects;
  }

  CatalogIndexCursor catalogIndexes() {
    return catalogIndexes;
  }

  RelationalScanCursor relational() {
    return relational;
  }

  RelationalScanCursor joinInnerRelational() {
    return joinInnerRelational;
  }

  StatusCode claim() {
    if (active) {
      return StatusCode.CONFLICT;
    }
    active = true;
    return StatusCode.OK;
  }

  StatusCode claimSorted(int rowCount) {
    if (active || rowCount < 0) {
      return StatusCode.CONFLICT;
    }
    sortedRowCount = rowCount;
    sortedRowIndex = 0;
    active = true;
    return StatusCode.OK;
  }

  StatusCode claimAggregate(
      long value,
      boolean nullValue,
      boolean transactionActive,
      long commitSequence) {
    if (active || commitSequence < 0) {
      return StatusCode.CONFLICT;
    }
    aggregateValue = value;
    aggregateNull = nullValue;
    aggregateTransactionActive = transactionActive;
    aggregateCommitSequence = commitSequence;
    active = true;
    return StatusCode.OK;
  }

  StatusCode claimSortedInput(int sortedInputRows) {
    if (active || sortedInputRows < -1) {
      return StatusCode.CONFLICT;
    }
    sortedRowCount = sortedInputRows >= 0 ? sortedInputRows : 0;
    sortedRowIndex = 0;
    active = true;
    return StatusCode.OK;
  }

  StatusCode claimExplain(
      boolean transactionActive,
      long commitSequence) {
    if (active || commitSequence < 0) {
      return StatusCode.CONFLICT;
    }
    aggregateTransactionActive = transactionActive;
    explainCommitSequence = commitSequence;
    planStepIndex = 0;
    active = true;
    return StatusCode.OK;
  }

  int currentPlanStep(int stepCount) {
    return planStepIndex < stepCount ? planStepIndex : -1;
  }

  void advancePlanStep() {
    planStepIndex++;
  }

  long explainCommitSequence() {
    return explainCommitSequence;
  }

  int currentSortedRow() {
    return sortedRowIndex < sortedRowCount ? sortedRowIndex : -1;
  }

  void advanceSortedRow() {
    sortedRowIndex++;
  }

  boolean joinMatched() {
    return joinMatched;
  }

  void matchJoin() {
    joinMatched = true;
  }

  boolean joinInnerScanActive() {
    return joinInnerScanActive;
  }

  void beginJoinInnerScan(long outerKey, long matchValue) {
    rememberJoinOuter(outerKey);
    joinMatchValue = matchValue;
    joinMatched = false;
    joinInnerScanActive = true;
  }

  void rememberJoinOuter(long outerKey) {
    joinOuterKey = outerKey;
  }

  void completeJoinInnerScan() {
    joinInnerScanActive = false;
  }

  long joinOuterKey() {
    return joinOuterKey;
  }

  long joinMatchValue() {
    return joinMatchValue;
  }

  void setJoinOuterProjectedValue(int index, long value, boolean isNull) {
    joinOuterProjectedValues[index] = value;
    if (isNull) {
      joinOuterNullMask |= 1L << index;
    } else {
      joinOuterNullMask &= ~(1L << index);
    }
  }

  long joinOuterProjectedValue(int index) {
    return joinOuterProjectedValues[index];
  }

  boolean joinOuterProjectedNull(int index) {
    return (joinOuterNullMask & 1L << index) != 0;
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

  boolean groupLookaheadNull() {
    return groupLookaheadNull;
  }

  long groupLookaheadAggregateValue() {
    return groupLookaheadAggregateValue;
  }

  boolean groupLookaheadAggregateNull() {
    return groupLookaheadAggregateNull;
  }

  void setGroupLookahead(
      long value,
      boolean nullValue,
      long aggregateValue,
      boolean aggregateNull) {
    groupLookaheadValue = value;
    groupLookaheadNull = nullValue;
    groupLookaheadAggregateValue = aggregateValue;
    groupLookaheadAggregateNull = aggregateNull;
    groupLookahead = true;
  }

  boolean hasDistinctValue() {
    return distinctValueAvailable;
  }

  long distinctValue() {
    return distinctValue;
  }

  boolean distinctValueNull() {
    return distinctValueNull;
  }

  void setDistinctValue(long value, boolean nullValue) {
    distinctValue = value;
    distinctValueNull = nullValue;
    distinctValueAvailable = true;
  }

  long aggregateValue() {
    return aggregateValue;
  }

  boolean aggregateNull() {
    return aggregateNull;
  }

  boolean aggregateTransactionActive() {
    return aggregateTransactionActive;
  }

  long aggregateCommitSequence() {
    return aggregateCommitSequence;
  }

  void complete() {
    active = false;
  }

  public boolean isActive() {
    return active;
  }
}
