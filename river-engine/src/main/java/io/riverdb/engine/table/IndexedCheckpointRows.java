package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.checkpoint.CheckpointState;

/** Selects a durable row-directory load or the bounded rebuild fallback. */
final class IndexedCheckpointRows {
  private IndexedCheckpointRows() {
  }

  static StatusCode load(IndexedTableKernel kernel, CheckpointState checkpoint) {
    if (kernel.rowDirectoryMatches(checkpoint.rowCount(), checkpoint.commitSequence())) {
      return kernel.loadRowDirectory(checkpoint.rowCount());
    }
    StatusCode status = kernel.rebuildRowLocations();
    return status.isOk() && kernel.rowCount() != checkpoint.rowCount()
        ? StatusCode.CORRUPTION : status;
  }

  static StatusCode capture(
      IndexedTableKernel kernel,
      CheckpointState checkpoint,
      long rowCount,
      IndexedCheckpointVersionSource versions) {
    if (!kernel.hasHistoricalVersions()) return StatusCode.OK;
    versions.bind(kernel);
    return checkpoint.attachVersionSource(
        versions, kernel.checkpointObsoleteVersionCount(), true);
  }
}
