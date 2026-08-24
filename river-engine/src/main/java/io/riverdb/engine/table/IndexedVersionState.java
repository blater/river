package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.checkpoint.CheckpointState;
import java.nio.ByteBuffer;

/** Owns bounded row-version metadata and operation/vacuum publication state. */
final class IndexedVersionState {
  private final LongPagedLongArray commitSequences =
      new LongPagedLongArray(IndexedTableLimits.MAX_ROWS);
  private final LongPagedLongArray previousRows =
      new LongPagedLongArray(IndexedTableLimits.MAX_ROWS);
  private final LongPagedBooleanArray deleted =
      new LongPagedBooleanArray(IndexedTableLimits.MAX_ROWS);
  private final LongPagedBooleanArray vacuumDeleted =
      new LongPagedBooleanArray(IndexedTableLimits.MAX_ROWS);
  private final LongPagedBooleanArray overrides =
      new LongPagedBooleanArray(IndexedTableLimits.MAX_ROWS);
  private final long[] operationPreviousRows =
      new long[IndexedTableLimits.MAX_OPERATION_ROWS];
  private final boolean[] operationDeleted =
      new boolean[IndexedTableLimits.MAX_OPERATION_ROWS];
  private int operationCount;
  private int obsoleteCount;
  private int deletedCount;
  private CheckpointState checkpointBase;

  int operationCount() {
    return operationCount;
  }

  long operationPreviousRow(int index) {
    return operationPreviousRows[index];
  }

  boolean operationDeleted(int index) {
    return operationDeleted[index];
  }

  int obsoleteCount() {
    return obsoleteCount;
  }

  boolean hasHistoricalVersions() {
    return obsoleteCount > 0 || deletedCount > 0;
  }

  long commitSequence(long rowId, long rowCount) {
    if (rowId <= 0 || rowId > rowCount) return 0;
    long value = commitSequences.get(rowId);
    return value != 0 ? value
        : checkpointBase == null ? 0 : checkpointBase.rowCommitSequence(rowId);
  }

  long previousRow(long rowId, long rowCount) {
    if (rowId <= 0 || rowId > rowCount) return 0;
    return overrides.get(rowId)
        ? previousRows.get(rowId)
        : checkpointBase == null ? 0 : checkpointBase.previousRowId(rowId);
  }

  boolean isDeleted(long rowId, long rowCount) {
    if (rowId <= 0 || rowId > rowCount) return false;
    return overrides.get(rowId)
        ? deleted.get(rowId)
        : checkpointBase != null && checkpointBase.isDeleted(rowId);
  }

  void beginOperation() {
    operationCount = 0;
  }

  boolean canStage(long previousRowId, boolean delete, long rowCount) {
    return operationCount < IndexedTableLimits.MAX_OPERATION_ROWS
        && previousRowId >= 0
        && previousRowId <= rowCount
        && (!delete || previousRowId > 0);
  }

  void stage(long previousRowId, boolean delete) {
    operationPreviousRows[operationCount] = previousRowId;
    operationDeleted[operationCount] = delete;
    operationCount++;
  }

  void recordCommitted(
      long rowId,
      long commitSequence,
      long previousRowId,
      boolean delete) {
    commitSequences.set(rowId, commitSequence);
    previousRows.set(rowId, previousRowId);
    deleted.set(rowId, delete);
    overrides.set(rowId, true);
    if (previousRowId > 0) {
      obsoleteCount++;
    }
    if (delete) {
      deletedCount++;
    }
  }

  void recordNewRows(long previousRowCount, long rowCount, long commitSequence) {
    for (long rowId = previousRowCount + 1; rowId <= rowCount; rowId++) {
      commitSequences.set(rowId, commitSequence);
      previousRows.set(rowId, 0);
      deleted.set(rowId, false);
      overrides.set(rowId, true);
    }
  }

  void recordOperation(long previousRowCount, long commitSequence) {
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
    checkpointBase = checkpoint;
    obsoleteCount = (int) Math.min(Integer.MAX_VALUE, checkpoint.obsoleteVersionCount());
    deletedCount = checkpoint.versionDirectoryRequired() ? 1 : 0;
  }

  void close() {
    if (checkpointBase != null) {
      checkpointBase.close();
      checkpointBase = null;
    }
  }

  StatusCode applyRecovered(
      ByteBuffer payload,
      int versionOffset,
      long previousRowCount,
      int versionCount,
      long commitSequence) {
    int recoveredObsolete = 0;
    for (int index = 0; index < versionCount; index++) {
      if (!IndexedWalCodec.validPageOperationVersion(payload, versionOffset)) {
        return StatusCode.CORRUPTION;
      }
      long previousRowId = IndexedWalCodec.pageVersionPreviousRowId(payload, versionOffset);
      boolean delete = IndexedWalCodec.pageVersionDeleted(payload, versionOffset);
      long rowId = previousRowCount + index + 1;
      if (previousRowId >= rowId || (delete && previousRowId == 0)) {
        return StatusCode.CORRUPTION;
      }
      commitSequences.set(rowId, commitSequence);
      previousRows.set(rowId, previousRowId);
      deleted.set(rowId, delete);
      overrides.set(rowId, true);
      if (previousRowId > 0) {
        recoveredObsolete++;
      }
      if (delete) {
        deletedCount++;
      }
      versionOffset += IndexedWalCodec.PAGE_OPERATION_VERSION_BYTES;
    }
    obsoleteCount += recoveredObsolete;
    return StatusCode.OK;
  }

  void recordVacuumDeleted(long rowId, boolean delete) {
    vacuumDeleted.set(rowId, delete);
  }

  void publishVacuum(long retainedRows, long commitSequence) {
    commitSequences.clear();
    previousRows.clear();
    deleted.clear();
    overrides.clear();
    checkpointBase = null;
    deletedCount = 0;
    for (long rowId = 1; rowId <= retainedRows; rowId++) {
      commitSequences.set(rowId, commitSequence);
      boolean rowDeleted = vacuumDeleted.get(rowId);
      deleted.set(rowId, rowDeleted);
      overrides.set(rowId, true);
      if (rowDeleted) deletedCount++;
      vacuumDeleted.set(rowId, false);
    }
    obsoleteCount = 0;
  }

  void cancelVacuum(long appliedRows) {
    for (long rowId = 1; rowId <= appliedRows; rowId++) {
      vacuumDeleted.set(rowId, false);
    }
  }

  long visibleRow(long rowId, long visibleCommitSequence, long rowCount) {
    long visible = rowId;
    while (visible > 0 && commitSequence(visible, rowCount) > visibleCommitSequence) {
      visible = previousRow(visible, rowCount);
    }
    return visible;
  }
}
