package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.checkpoint.CheckpointVersionSource;

/** Streams stable kernel version records into a checkpoint-owned result carrier. */
final class IndexedCheckpointVersionSource implements CheckpointVersionSource {
  private final IndexedVersionRecord version = new IndexedVersionRecord();
  private IndexedTableKernel kernel;

  void bind(IndexedTableKernel source) {
    kernel = source;
  }

  @Override
  public StatusCode readVersion(long rowId) {
    return kernel == null
        ? StatusCode.INVALID_EXTERNAL_INPUT : kernel.readVersion(rowId, version);
  }

  @Override public long commitSequence() { return version.commitSequence(); }
  @Override public long previousRowId() { return version.previousRowId(); }
  @Override public boolean deleted() { return version.deleted(); }

  @Override
  public int versionPageCountUpperBound() {
    return kernel == null ? 0 : kernel.checkpointVersionPageCountUpperBound();
  }

  @Override
  public void resetVersionPages() {
    if (kernel != null) kernel.resetCheckpointVersionPages();
  }

  @Override
  public long nextVersionPageId() {
    return kernel == null ? -1 : kernel.nextCheckpointVersionPageId();
  }
}
