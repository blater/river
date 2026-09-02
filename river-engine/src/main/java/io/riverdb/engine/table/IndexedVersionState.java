package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.checkpoint.CheckpointState;
import io.riverdb.engine.checkpoint.CheckpointVersionResult;
import java.nio.ByteBuffer;

/** Owns fixed-record row-version metadata without one resident record per row. */
final class IndexedVersionState {
  private final IndexedVersionDirectory directory;
  private final IndexedVersionRows rows;
  private final IndexedVersionWalApply walApply;
  private final IndexedVersionOperation operation = new IndexedVersionOperation();
  private long obsoleteCount;
  private long deletedCount;
  private CheckpointState checkpointBase;
  private final CheckpointVersionResult checkpointVersion =
      new CheckpointVersionResult();
  private int checkpointBasePageCursor;
  private int checkpointDeltaPageCursor;

  IndexedVersionState(
      IndexedRowDirectory rowDirectory, IndexedVersionDirectory versionDirectory) {
    rows = new IndexedVersionRows(rowDirectory);
    directory = versionDirectory;
    walApply = new IndexedVersionWalApply(directory);
  }

  IndexedVersionRows rows() { return rows; }
  IndexedVersionOperation operation() { return operation; }

  IndexedVersionDirectory directory() {
    return directory;
  }

  int obsoleteCount() {
    return (int) Math.min(Integer.MAX_VALUE, obsoleteCount);
  }

  long checkpointObsoleteCount() {
    return obsoleteCount;
  }

  boolean hasHistoricalVersions() {
    return obsoleteCount > 0 || deletedCount > 0;
  }

  int checkpointVersionPageCountUpperBound() {
    int baseCount = checkpointBase == null ? 0 : checkpointBase.versionPageCount();
    int deltaCount = directory.checkpointPageCount();
    int base = 0;
    int delta = 0;
    int merged = 0;
    while (base < baseCount || delta < deltaCount) {
      long baseId = base < baseCount
          ? checkpointBase.versionPageId(base) : Long.MAX_VALUE;
      long deltaId = delta < deltaCount
          ? directory.checkpointPageId(delta) : Long.MAX_VALUE;
      long next = Math.min(baseId, deltaId);
      if (baseId == next) base++;
      if (deltaId == next) delta++;
      if (merged == Integer.MAX_VALUE) return merged;
      merged++;
    }
    return merged;
  }

  void resetCheckpointVersionPages() {
    checkpointBasePageCursor = 0;
    checkpointDeltaPageCursor = 0;
  }

  long nextCheckpointVersionPageId() {
    int baseCount = checkpointBase == null ? 0 : checkpointBase.versionPageCount();
    int deltaCount = directory.checkpointPageCount();
    long base = checkpointBasePageCursor < baseCount
        ? checkpointBase.versionPageId(checkpointBasePageCursor) : Long.MAX_VALUE;
    long delta = checkpointDeltaPageCursor < deltaCount
        ? directory.checkpointPageId(checkpointDeltaPageCursor) : Long.MAX_VALUE;
    if (base == Long.MAX_VALUE && delta == Long.MAX_VALUE) return -1;
    long next = Math.min(base, delta);
    if (base == next) checkpointBasePageCursor++;
    if (delta == next) checkpointDeltaPageCursor++;
    return next;
  }

  StatusCode lookup(long rowId, long rowCount, IndexedVersionRecord result) {
    if (result == null || rowId <= 0 || rowId > rowCount) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = directory.lookup(rowId, result);
    if (!status.isOk() || result.available()) return status;
    if (checkpointBase == null) return StatusCode.CORRUPTION;
    StatusCode checkpointStatus = checkpointBase.readVersion(rowId, checkpointVersion);
    if (!checkpointStatus.isOk()) return checkpointStatus;
    result.set(
        checkpointVersion.commitSequence(),
        checkpointVersion.previousRowId(),
        checkpointVersion.deleted());
    if (result.commitSequence() <= 0) return StatusCode.CORRUPTION;
    return StatusCode.OK;
  }

  StatusCode admitRows(long firstRowId, int count) {
    return operation.admit(firstRowId, count, rows, directory);
  }

  StatusCode publishOperationRows(long previousRowCount) {
    return operation.publishRows(previousRowCount, rows);
  }

  StatusCode recordCommitted(
      long rowId,
      long commitSequence,
      long previousRowId,
      boolean delete) {
    StatusCode status = directory.set(rowId, commitSequence, previousRowId, delete);
    if (!status.isOk()) return status;
    if (previousRowId > 0) obsoleteCount++;
    if (delete) deletedCount++;
    return StatusCode.OK;
  }

  StatusCode recordNewRows(long previousRowCount, long rowCount, long commitSequence) {
    for (long rowId = previousRowCount + 1; rowId <= rowCount; rowId++) {
      StatusCode status = directory.set(rowId, commitSequence, 0, false);
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  StatusCode recordOperation(long previousRowCount, long commitSequence) {
    return recordOperation(previousRowCount, 0, operation.count(), commitSequence);
  }

  StatusCode recordOperation(
      long groupBaseRow, int first, int count, long commitSequence) {
    if (first < 0 || count < 0 || first > operation.count() - count) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int index = first; index < first + count; index++) {
      StatusCode status = recordCommitted(
          groupBaseRow + index + 1,
          commitSequence,
          operation.previousRow(index),
          operation.deleted(index));
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  void clearOperation() {
    operation.clear();
  }

  StatusCode load(CheckpointState checkpoint) {
    StatusCode status = directory.clear();
    if (!status.isOk()) return status;
    checkpointBase = checkpoint;
    obsoleteCount = checkpoint.obsoleteVersionCount();
    deletedCount = checkpoint.versionDirectoryRequired() ? 1 : 0;
    return StatusCode.OK;
  }

  void close() {
    operation.release();
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
    StatusCode status = walApply.apply(
        payload, versionOffset, previousRowCount, versionCount, commitSequence);
    if (status.isOk()) {
      obsoleteCount += walApply.obsolete();
      deletedCount += walApply.deleted();
    }
    return status;
  }

  StatusCode recordVacuumDeleted(long rowId, boolean delete) {
    return directory.setVacuumDeleted(rowId, delete);
  }

  StatusCode publishVacuum(long retainedRows, long commitSequence) {
    StatusCode status = walApply.publishVacuum(retainedRows, commitSequence);
    if (status.isOk()) {
      checkpointBase = null;
      deletedCount = walApply.deleted();
      obsoleteCount = 0;
    }
    return status;
  }

  StatusCode cancelVacuum(long appliedRows) {
    return walApply.cancelVacuum(appliedRows);
  }

}
