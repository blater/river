package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.checkpoint.CheckpointState;
import java.nio.ByteBuffer;

/** Owns bounded row-version metadata and operation/vacuum publication state. */
final class IndexedVersionState {
  private final PagedLongArray commitSequences = new PagedLongArray(IndexedTableLimits.MAX_ROWS);
  private final PagedIntArray previousRows = new PagedIntArray(IndexedTableLimits.MAX_ROWS);
  private final PagedBooleanArray deleted = new PagedBooleanArray(IndexedTableLimits.MAX_ROWS);
  private final PagedBooleanArray vacuumDeleted = new PagedBooleanArray(IndexedTableLimits.MAX_ROWS);
  private final int[] operationPreviousRows =
      new int[IndexedTableLimits.MAX_OPERATION_ROWS];
  private final boolean[] operationDeleted =
      new boolean[IndexedTableLimits.MAX_OPERATION_ROWS];
  private int operationCount;
  private int obsoleteCount;

  int operationCount() {
    return operationCount;
  }

  int operationPreviousRow(int index) {
    return operationPreviousRows[index];
  }

  boolean operationDeleted(int index) {
    return operationDeleted[index];
  }

  int obsoleteCount() {
    return obsoleteCount;
  }

  long commitSequence(int rowId, int rowCount) {
    return rowId > 0 && rowId <= rowCount ? commitSequences.get(rowId) : 0;
  }

  int previousRow(int rowId, int rowCount) {
    return rowId > 0 && rowId <= rowCount ? previousRows.get(rowId) : 0;
  }

  boolean isDeleted(int rowId, int rowCount) {
    return rowId > 0 && rowId <= rowCount && deleted.get(rowId);
  }

  void beginOperation() {
    operationCount = 0;
  }

  boolean canStage(int previousRowId, boolean delete, int rowCount) {
    return operationCount < IndexedTableLimits.MAX_OPERATION_ROWS
        && previousRowId >= 0
        && previousRowId <= rowCount
        && (!delete || previousRowId > 0);
  }

  void stage(int previousRowId, boolean delete) {
    operationPreviousRows[operationCount] = previousRowId;
    operationDeleted[operationCount] = delete;
    operationCount++;
  }

  void recordCommitted(
      int rowId,
      long commitSequence,
      int previousRowId,
      boolean delete) {
    commitSequences.set(rowId, commitSequence);
    previousRows.set(rowId, previousRowId);
    deleted.set(rowId, delete);
    if (previousRowId > 0) {
      obsoleteCount++;
    }
  }

  void recordNewRows(int previousRowCount, int rowCount, long commitSequence) {
    for (int rowId = previousRowCount + 1; rowId <= rowCount; rowId++) {
      commitSequences.set(rowId, commitSequence);
      previousRows.set(rowId, 0);
      deleted.set(rowId, false);
    }
  }

  void recordOperation(int previousRowCount, long commitSequence) {
    for (int index = 0; index < operationCount; index++) {
      recordCommitted(
          previousRowCount + index + 1,
          commitSequence,
          operationPreviousRows[index],
          operationDeleted[index]);
    }
  }

  void clearOperation() {
    for (int index = 0; index < operationCount; index++) {
      operationPreviousRows[index] = 0;
      operationDeleted[index] = false;
    }
    operationCount = 0;
  }

  void load(CheckpointState checkpoint) {
    obsoleteCount = 0;
    for (int rowId = 1; rowId <= checkpoint.rowCount(); rowId++) {
      commitSequences.set(rowId, checkpoint.rowCommitSequence(rowId));
      previousRows.set(rowId, checkpoint.previousRowId(rowId));
      deleted.set(rowId, checkpoint.isDeleted(rowId));
      if (previousRows.get(rowId) > 0) {
        obsoleteCount++;
      }
    }
  }

  StatusCode applyRecovered(
      ByteBuffer payload,
      int versionOffset,
      int previousRowCount,
      int versionCount,
      long commitSequence) {
    int recoveredObsolete = 0;
    for (int index = 0; index < versionCount; index++) {
      if (!IndexedWalCodec.validPageOperationVersion(payload, versionOffset)) {
        return StatusCode.CORRUPTION;
      }
      int previousRowId = IndexedWalCodec.pageVersionPreviousRowId(payload, versionOffset);
      boolean delete = IndexedWalCodec.pageVersionDeleted(payload, versionOffset);
      int rowId = previousRowCount + index + 1;
      if (previousRowId >= rowId || (delete && previousRowId == 0)) {
        return StatusCode.CORRUPTION;
      }
      commitSequences.set(rowId, commitSequence);
      previousRows.set(rowId, previousRowId);
      deleted.set(rowId, delete);
      if (previousRowId > 0) {
        recoveredObsolete++;
      }
      versionOffset += IndexedWalCodec.PAGE_OPERATION_VERSION_BYTES;
    }
    obsoleteCount += recoveredObsolete;
    return StatusCode.OK;
  }

  void recordVacuumDeleted(int rowId, boolean delete) {
    vacuumDeleted.set(rowId, delete);
  }

  void publishVacuum(int retainedRows, long commitSequence) {
    commitSequences.clear();
    previousRows.clear();
    deleted.clear();
    for (int rowId = 1; rowId <= retainedRows; rowId++) {
      commitSequences.set(rowId, commitSequence);
      deleted.set(rowId, vacuumDeleted.get(rowId));
      vacuumDeleted.set(rowId, false);
    }
    obsoleteCount = 0;
  }

  void cancelVacuum(int appliedRows) {
    for (int rowId = 1; rowId <= appliedRows; rowId++) {
      vacuumDeleted.set(rowId, false);
    }
  }

  int visibleRow(int rowId, long visibleCommitSequence, int rowCount) {
    int visible = rowId;
    while (visible > 0 && commitSequence(visible, rowCount) > visibleCommitSequence) {
      visible = previousRow(visible, rowCount);
    }
    return visible;
  }
}
