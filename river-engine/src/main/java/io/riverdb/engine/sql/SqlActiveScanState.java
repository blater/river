package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.CatalogObjectCursor;
import io.riverdb.engine.relational.CatalogIndexCursor;
import io.riverdb.engine.relational.RelationalScanCursor;

/** Reusable physical operator progress for the one active query in a SQL session. */
final class SqlActiveScanState {
  private final SqlRetainedArrayAllocator allocator;
  private final RelationalScanCursor relational = new RelationalScanCursor();
  private final CatalogObjectCursor catalogObjects = new CatalogObjectCursor();
  private final CatalogIndexCursor catalogIndexes = new CatalogIndexCursor();
  private final SqlGroupLookaheadState groupLookahead;
  private boolean distinctValueAvailable;
  private boolean aggregateTransactionActive;
  private boolean aggregateNull;
  private boolean aggregateAvailable;
  private char[] aggregateText;
  private int aggregateTextLength;
  private long aggregateHigh;
  private long aggregateValue;
  private long aggregateCommitSequence;
  private long explainCommitSequence;
  private long sortedRowCount;
  private long sortedRowIndex;
  private int planStepIndex;
  private boolean active;
  private StatusCode terminalStatus;

  SqlActiveScanState() { this(SqlRetainedArrayAllocator.STANDARD); }

  SqlActiveScanState(SqlRetainedArrayAllocator retainedAllocator) {
    allocator = retainedAllocator;
    groupLookahead = new SqlGroupLookaheadState(retainedAllocator);
  }

  public StatusCode reset() {
    if (active) {
      return StatusCode.CONFLICT;
    }
    groupLookahead.reset();
    distinctValueAvailable = false;
    aggregateTransactionActive = false;
    aggregateNull = false;
    aggregateAvailable = false;
    if (aggregateText != null) {
      for (int index = 0; index < aggregateTextLength; index++) {
        aggregateText[index] = 0;
      }
    }
    aggregateTextLength = 0;
    aggregateHigh = 0;
    aggregateValue = 0;
    aggregateCommitSequence = 0;
    explainCommitSequence = 0;
    sortedRowCount = 0;
    sortedRowIndex = 0;
    planStepIndex = 0;
    terminalStatus = null;
    StatusCode status = relational.reset();
    StatusCode catalog = catalogObjects.reset();
    StatusCode indexes = catalogIndexes.reset();
    return !status.isOk()
        ? status : !catalog.isOk() ? catalog : indexes;
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

  StatusCode claim() {
    if (active) {
      return StatusCode.CONFLICT;
    }
    active = true;
    return StatusCode.OK;
  }

  StatusCode claimSorted(long rowCount) {
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
    int length = result.textLengthAt(0);
    char[] nextText = aggregateText;
    if (length >= 0 && nextText == null) {
      try {
        nextText = allocator.characters(
            io.riverdb.engine.api.CommandResult.MAXIMUM_TEXT_CHARACTERS);
      } catch (OutOfMemoryError error) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
    }
    aggregateAvailable = result.hasValue();
    aggregateHigh = result.highValueAt(0);
    aggregateValue = result.value();
    aggregateNull = result.isNull(0);
    aggregateTransactionActive = result.transactionActive();
    aggregateCommitSequence = result.commitSequence();
    if (length >= 0) {
      aggregateText = nextText;
      aggregateTextLength = result.copyTextAt(0, aggregateText, 0);
    }
    active = true;
    return StatusCode.OK;
  }

  StatusCode reserveAggregateText(boolean required) {
    if (!required || aggregateText != null) return StatusCode.OK;
    try {
      char[] next = allocator.characters(
          io.riverdb.engine.api.CommandResult.MAXIMUM_TEXT_CHARACTERS);
      aggregateText = next;
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  StatusCode claimSortedInput(long sortedInputRows) {
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
    return sortedRowIndex < sortedRowCount && sortedRowIndex <= Integer.MAX_VALUE
        ? (int) sortedRowIndex : -1;
  }

  long currentSortedOrdinal() {
    return sortedRowIndex < sortedRowCount ? sortedRowIndex : -1;
  }

  void advanceSortedRow() {
    sortedRowIndex++;
  }

  SqlGroupLookaheadState groupLookahead() { return groupLookahead; }

  boolean hasDistinctValue() {
    return distinctValueAvailable;
  }

  void markDistinctValue() { distinctValueAvailable = true; }

  long aggregateValue() {
    return aggregateValue;
  }

  long aggregateHigh() {
    return aggregateHigh;
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
