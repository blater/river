package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.checkpoint.CheckpointState;
import java.nio.ByteBuffer;

/** Owns version-directory operations that do not depend on mutable kernel row counters. */
class IndexedKernelVersions {
  protected final IndexedVersionState versions;

  IndexedKernelVersions(IndexedVersionState versionState) { versions = versionState; }

  int operationVersionCount() { return versions.operation().count(); }

  long operationPreviousRowId(int index) { return versions.operation().previousRow(index); }

  boolean operationDeleted(int index) { return versions.operation().deleted(index); }

  StatusCode recordVacuumDeleted(long rowId, boolean deleted) {
    return versions.recordVacuumDeleted(rowId, deleted);
  }

  boolean hasHistoricalVersions() { return versions.hasHistoricalVersions(); }

  int checkpointVersionPageCountUpperBound() {
    return versions.checkpointVersionPageCountUpperBound();
  }

  void resetCheckpointVersionPages() { versions.resetCheckpointVersionPages(); }

  long nextCheckpointVersionPageId() { return versions.nextCheckpointVersionPageId(); }

  void clearOperationVersions() { versions.clearOperation(); }

  StatusCode loadCheckpointVersions(CheckpointState checkpoint) {
    return versions.load(checkpoint);
  }

  StatusCode closeCheckpointVersions() { return versions.close(); }

  StatusCode applyRecoveredVersions(
      ByteBuffer payload, int offset, long previousRows, int count, long commitSequence) {
    return versions.applyRecovered(payload, offset, previousRows, count, commitSequence);
  }

  StatusCode publishVacuumVersions(long retainedRows, long commitSequence) {
    return versions.publishVacuum(retainedRows, commitSequence);
  }

  StatusCode cancelVacuumVersions(long appliedRows) {
    return versions.cancelVacuum(appliedRows);
  }

  IndexedVersionState versionState() { return versions; }

  StatusCode closeSidecars() {
    StatusCode versionClose = versions.directory().close();
    StatusCode rowClose = versions.rows().directory().close();
    return !versionClose.isOk() ? versionClose : rowClose;
  }

  boolean sidecarsDirty() {
    return versions.rows().directory().hasDirtyPages() || versions.directory().hasDirtyPages();
  }

  StatusCode rowDirectoryStatus() { return versions.rows().directory().lastStatus(); }

  StatusCode versionDirectoryStatus() { return versions.directory().lastStatus(); }
}
