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
  private boolean aggregateAvailable;
  private char[] aggregateText;
  private int aggregateTextLength;
  private long aggregateValue;
  private long aggregateCommitSequence;
  private long explainCommitSequence;
  private long groupLookaheadValue;
  private long groupLookaheadAggregateValue;
  private boolean groupLookaheadAggregateNull;
  private final long[] groupLookaheadValues =
      new long[TableSchema.MAXIMUM_COLUMNS];
  private long groupLookaheadNullMask;
  private long distinctValue;
  private long joinOuterKey;
  private long joinMatchValue;
  private long joinOuterNullMask;
  private int sortedRowCount;
  private int sortedRowIndex;
  private int planStepIndex;
  private boolean active;
  private StatusCode terminalStatus;

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
    aggregateAvailable = false;
    if (aggregateText != null) {
      for (int index = 0; index < aggregateTextLength; index++) {
        aggregateText[index] = 0;
      }
    }
    aggregateTextLength = 0;
    aggregateValue = 0;
    aggregateCommitSequence = 0;
    explainCommitSequence = 0;
    groupLookaheadValue = 0;
    groupLookaheadAggregateValue = 0;
    groupLookaheadAggregateNull = false;
    groupLookaheadNullMask = 0;
    for (int index = 0; index < groupLookaheadValues.length; index++) {
      groupLookaheadValues[index] = 0;
    }
    distinctValue = 0;
    joinOuterKey = 0;
    joinMatchValue = 0;
    joinOuterNullMask = 0;
    sortedRowCount = 0;
    sortedRowIndex = 0;
    planStepIndex = 0;
    terminalStatus = null;
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

  StatusCode claimAggregate(SqlExecutionResult result) {
    if (active || result == null || result.commitSequence() < 0) {
      return StatusCode.CONFLICT;
    }
    aggregateAvailable = result.hasValue();
    aggregateValue = result.value();
    aggregateNull = result.isNull(0);
    aggregateTransactionActive = result.transactionActive();
    aggregateCommitSequence = result.commitSequence();
    int length = result.textLengthAt(0);
    if (length >= 0) {
      if (aggregateText == null) {
        aggregateText = new char[io.riverdb.engine.api.CommandResult.MAXIMUM_TEXT_CHARACTERS];
      }
      aggregateTextLength = result.copyTextAt(0, aggregateText, 0);
    }
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

  void setGroupLookahead(long[] values, int count, long nullMask) {
    System.arraycopy(values, 0, groupLookaheadValues, 0, count);
    groupLookaheadNullMask = nullMask;
    groupLookahead = true;
  }

  void takeGroupLookahead(long[] values, int count) {
    System.arraycopy(groupLookaheadValues, 0, values, 0, count);
    groupLookahead = false;
  }

  long groupLookaheadNullMask() { return groupLookaheadNullMask; }

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

  boolean aggregateAvailable() { return aggregateAvailable; }
  int aggregateTextLength() { return aggregateTextLength; }
  char[] aggregateText() { return aggregateText; }

  boolean aggregateTransactionActive() {
    return aggregateTransactionActive;
  }

  long aggregateCommitSequence() {
    return aggregateCommitSequence;
  }

  void complete() {
    active = false;
  }

  void fail(StatusCode status) {
    if (terminalStatus == null && status != null && !status.isOk()) {
      terminalStatus = status;
    }
  }

  StatusCode terminalStatus() {
    return terminalStatus;
  }

  public boolean isActive() {
    return active;
  }
}
