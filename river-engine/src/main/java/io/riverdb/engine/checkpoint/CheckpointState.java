package io.riverdb.engine.checkpoint;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.platform.file.DurableFile;

/** Caller-owned checkpoint authority with sparse MVCC metadata delegated to bounded stores. */
public final class CheckpointState {
  public static final int MAXIMUM_ROWS = 64 * 1024;
  public static final long MAXIMUM_RUNTIME_ROWS = 0xFFFF_FFFEL;
  static final int VERSION_PAGE_SHIFT = CheckpointVersionFormat.PAGE_SHIFT;
  static final int VERSION_PAGE_ROWS = CheckpointVersionFormat.PAGE_ROWS;
  static final int VERSION_SEGMENT_BYTES = CheckpointVersionFormat.SEGMENT_BYTES;

  private final CheckpointVersionOverrides overrides = new CheckpointVersionOverrides();
  private final CheckpointVersionDirectory directory = new CheckpointVersionDirectory();
  private final CheckpointLogicalRowIdDirectory logicalRowIds =
      new CheckpointLogicalRowIdDirectory();
  private final CheckpointMetadata metadata = new CheckpointMetadata();
  private CheckpointVersionSource versionSource;
  private CheckpointLogicalRowIdSource logicalRowIdSource;

  public void reset() {
    versionSource = null;
    logicalRowIdSource = null;
    metadata.clear();
    overrides.clear();
    directory.close();
    logicalRowIds.reset();
  }

  public StatusCode set(
      DatabaseIncarnation incarnation, WalGeneration generation, long id,
      long committedAt, long maximumTx, int pages, long rows) {
    if (rows > MAXIMUM_ROWS) return StatusCode.INVALID_EXTERNAL_INPUT;
    return setLarge(incarnation, generation, id, committedAt, maximumTx, pages, rows);
  }

  public StatusCode setLarge(
      DatabaseIncarnation incarnation, WalGeneration generation, long id,
      long committedAt, long maximumTx, int pages, long rows) {
    reset();
    StatusCode status = metadata.set(
        incarnation, generation, id, committedAt, maximumTx, pages, rows);
    if (status.isOk()) logicalRowIdSource = logicalRowIds;
    return status;
  }

  public StatusCode setRowVersion(
      long rowId, long committedAt, long previousRowId, boolean deleted) {
    if (!validVersion(rowId, committedAt, previousRowId)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = overrides.set(rowId, committedAt, previousRowId, deleted);
    if (!status.isOk()) return status;
    metadata.obsoleteVersionCount(overrides.obsoleteCount());
    if (previousRowId > 0 || deleted || committedAt != commitSequence()) {
      metadata.requireVersions();
    }
    return StatusCode.OK;
  }

  public StatusCode setDeleted(long rowId) {
    if (!isAvailable() || rowId <= 0 || rowId > rowCount()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = overrides.set(rowId, commitSequence(), 0, true);
    if (status.isOk()) metadata.requireVersions();
    return status;
  }

  public StatusCode readVersion(long rowId, CheckpointVersionResult result) {
    if (result == null || rowId <= 0 || rowId > rowCount()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    if (versionSource != null) return readSource(rowId, result);
    if (overrides.read(rowId, commitSequence(), result)) return StatusCode.OK;
    return directory.read(rowId, commitSequence(), result);
  }

  public StatusCode attachVersionSource(
      CheckpointVersionSource source, long obsoleteVersions, boolean required) {
    if (!isAvailable() || source == null || obsoleteVersions < 0 || !required) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    directory.close();
    versionSource = source;
    metadata.obsoleteVersionCount(obsoleteVersions);
    metadata.requireVersions();
    return StatusCode.OK;
  }

  public StatusCode attachLogicalRowIdSource(CheckpointLogicalRowIdSource source) {
    if (!isAvailable() || source == null || source.floorCount() < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    logicalRowIdSource = source;
    return StatusCode.OK;
  }

  StatusCode attachVersionDirectory(
      DurableFile file, long[] pageIds, long[] offsets, int count) {
    if (!isAvailable()) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = directory.bind(
        file, pageIds, offsets, count, database().high(), database().low(),
        walGeneration().value(), checkpointId(), commitSequence(), rowCount());
    if (status.isOk()) metadata.requireVersions();
    return status;
  }

  public DatabaseIncarnation database() { return metadata.database(); }
  public WalGeneration walGeneration() { return metadata.walGeneration(); }
  public long checkpointId() { return metadata.checkpointId(); }
  public long commitSequence() { return metadata.commitSequence(); }
  public long maximumTransactionId() { return metadata.maximumTransactionId(); }
  public int pageCount() { return metadata.pageCount(); }
  public long rowCount() { return metadata.rowCount(); }
  public boolean isAvailable() { return metadata.available(); }
  public long obsoleteVersionCount() { return metadata.obsoleteVersionCount(); }
  public boolean versionDirectoryRequired() { return metadata.versionsRequired(); }
  public int versionPageCount() { return directory.available() ? directory.count() : overrides.count(); }
  public long versionPageId(int index) {
    return directory.available() ? directory.pageId(index) : overrides.pageId(index);
  }
  public CheckpointLogicalRowIdSource logicalRowIdSource() {
    return logicalRowIdSource;
  }
  public void close() { directory.close(); }

  void setObsoleteVersionCount(long value) { metadata.obsoleteVersionCount(value); }
  int versionPageCountUpperBound() {
    return versionSource == null ? overrides.count() : versionSource.versionPageCountUpperBound();
  }
  void resetVersionPages() {
    if (versionSource == null) overrides.resetCursor();
    else versionSource.resetVersionPages();
  }
  CheckpointLogicalRowIdDirectory logicalRowIdDirectory() { return logicalRowIds; }
  void attachLoadedLogicalRowIds() { logicalRowIdSource = logicalRowIds; }
  long nextVersionPageId() {
    return versionSource == null ? overrides.nextPageId() : versionSource.nextVersionPageId();
  }

  private StatusCode readSource(long rowId, CheckpointVersionResult result) {
    StatusCode status = versionSource.readVersion(rowId);
    if (status.isOk()) {
      result.set(versionSource.commitSequence(), versionSource.previousRowId(),
          versionSource.deleted());
    }
    return status;
  }

  private boolean validVersion(long rowId, long committedAt, long previousRowId) {
    return isAvailable() && rowId > 0 && rowId <= rowCount()
        && committedAt > 0 && committedAt <= commitSequence()
        && previousRowId >= 0 && previousRowId < rowId;
  }
}
