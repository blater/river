package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import java.nio.ByteBuffer;

/** Applies recovered and vacuum version records to the durable sidecar. */
final class IndexedVersionWalApply {
  private final IndexedVersionDirectory directory;
  private int obsolete;
  private int deleted;

  IndexedVersionWalApply(IndexedVersionDirectory versionDirectory) {
    directory = versionDirectory;
  }

  StatusCode apply(
      ByteBuffer payload, int offset, long previousRows, int count, long commitSequence) {
    obsolete = 0;
    deleted = 0;
    for (int index = 0; index < count; index++) {
      if (!IndexedWalCodec.validPageOperationVersion(payload, offset)) {
        return StatusCode.CORRUPTION;
      }
      long previous = IndexedWalCodec.pageVersionPreviousRowId(payload, offset);
      boolean tombstone = IndexedWalCodec.pageVersionDeleted(payload, offset);
      long rowId = previousRows + index + 1;
      if (previous >= rowId || tombstone && previous == 0) return StatusCode.CORRUPTION;
      StatusCode status = directory.set(rowId, commitSequence, previous, tombstone);
      if (!status.isOk()) return status;
      if (tombstone) deleted++;
      if (previous > 0) obsolete++;
      offset += IndexedWalCodec.PAGE_OPERATION_VERSION_BYTES;
    }
    return StatusCode.OK;
  }

  StatusCode publishVacuum(long retainedRows, long commitSequence) {
    deleted = 0;
    StatusCode status = directory.beginVacuumPublication(retainedRows);
    for (long rowId = 1; status.isOk() && rowId <= retainedRows; rowId++) {
      boolean present = directory.read(rowId);
      if (!present && !directory.lastStatus().isOk()) return directory.lastStatus();
      boolean tombstone = present && directory.vacuumDeleted();
      status = directory.set(rowId, commitSequence, 0, tombstone);
      if (tombstone) deleted++;
    }
    return status;
  }

  StatusCode cancelVacuum(long appliedRows) {
    for (long rowId = 1; rowId <= appliedRows; rowId++) {
      StatusCode status = directory.clearVacuumDeleted(rowId);
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  int obsolete() { return obsolete; }
  int deleted() { return deleted; }
}
