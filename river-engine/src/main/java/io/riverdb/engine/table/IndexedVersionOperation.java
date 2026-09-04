package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.runtime.DatabaseResourcePlan;
import io.riverdb.engine.runtime.DatabaseVersionWorkspacePlan;

/** Bounded staged version metadata for one logical table operation. */
final class IndexedVersionOperation {
  private final long maximumRetainedBytes;
  private final IndexedLongChunks previousRows;
  private final IndexedIntChunks deleted;
  private final IndexedIntChunks pageIds;
  private final IndexedIntChunks slots;
  private int count;
  private long retainedBytes;

  IndexedVersionOperation(DatabaseResourcePlan resourcePlan) {
    this(resourcePlan.versionWorkspace());
  }

  IndexedVersionOperation(DatabaseVersionWorkspacePlan workspacePlan) {
    int maximum = workspacePlan.maximumOperations();
    maximumRetainedBytes = workspacePlan.maximumRetainedBytes();
    previousRows = new IndexedLongChunks(maximum);
    deleted = new IndexedIntChunks(maximum);
    pageIds = new IndexedIntChunks(maximum);
    slots = new IndexedIntChunks(maximum);
  }

  int count() { return count; }
  long previousRow(int index) { return previousRows.get(index); }
  boolean deleted(int index) { return deleted.get(index) != 0; }
  int pageId(int index) { return pageIds.get(index); }
  int slot(int index) { return slots.get(index); }
  void begin() { count = 0; }

  StatusCode reserve(int required) {
    if (required < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (required == 0) return StatusCode.OK;
    long targetBytes = accountedBytesForCapacity(required);
    if (targetBytes < 0 || targetBytes > maximumRetainedBytes) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    if (targetBytes > retainedBytes) retainedBytes = targetBytes;
    StatusCode status = previousRows.reserve(required);
    if (status.isOk()) status = deleted.reserve(required);
    if (status.isOk()) status = pageIds.reserve(required);
    return status.isOk() ? slots.reserve(required) : status;
  }

  static int required(int scalarVersions, int registryVersions) {
    if (scalarVersions < 0 || registryVersions < 0
        || scalarVersions > Integer.MAX_VALUE - registryVersions) return -1;
    return scalarVersions + registryVersions;
  }

  static int required(IndexedRelationalMutationBuffer mutations) {
    if (mutations == null || !mutations.sealed()) return -1;
    int required = 0;
    for (int operation = 0; operation < mutations.suboperationCount(); operation++) {
      int additional = mutations.suboperationDescriptorAt(operation) < 0
          ? mutations.suboperationMutationCountAt(operation) : 1;
      required = required(required, additional);
      if (required < 0) return -1;
    }
    return required;
  }

  boolean canStage(long previous, boolean tombstone, long rowCount) {
    return count < previousRows.allocatedCapacity() && previous >= 0 && previous <= rowCount
        && (!tombstone || previous > 0);
  }

  void stage(long previous, boolean tombstone, int pageId, int slot) {
    previousRows.set(count, previous);
    deleted.set(count, tombstone ? 1 : 0);
    pageIds.set(count, pageId);
    slots.set(count, slot);
    count++;
  }

  StatusCode admit(
      long firstRowId, int rowCount,
      IndexedVersionRows rows, IndexedVersionDirectory versions) {
    if (firstRowId <= 0 || rowCount <= 0
        || firstRowId > IndexedTableLimits.MAX_ROWS - rowCount + 1) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    long lastRowId = firstRowId + rowCount - 1;
    StatusCode status = rows.directory().ensure(firstRowId);
    if (status.isOk() && lastRowId != firstRowId) status = rows.directory().ensure(lastRowId);
    if (status.isOk()) status = versions.ensure(firstRowId);
    return status.isOk() && lastRowId != firstRowId ? versions.ensure(lastRowId) : status;
  }

  StatusCode publishRows(long previousRowCount, IndexedVersionRows rows) {
    return publishRows(previousRowCount, 0, count, rows);
  }

  StatusCode publishRows(
      long groupBaseRow, int first, int rangeCount, IndexedVersionRows rows) {
    if (first < 0 || rangeCount < 0 || first > count - rangeCount) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int index = first; index < first + rangeCount; index++) {
      StatusCode status = rows.set(
          groupBaseRow + index + 1, pageIds.get(index), slots.get(index));
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  void clear() {
    for (int index = 0; index < count; index++) {
      previousRows.set(index, 0);
      deleted.set(index, 0);
      pageIds.set(index, 0);
      slots.set(index, 0);
    }
    count = 0;
  }

  long accountedBytes() {
    return previousRows.allocatedBytes() + deleted.allocatedBytes()
        + pageIds.allocatedBytes() + slots.allocatedBytes();
  }

  StatusCode release() {
    previousRows.release();
    deleted.release();
    pageIds.release();
    slots.release();
    count = 0;
    retainedBytes = 0;
    return StatusCode.OK;
  }

  long retainedBytes() { return retainedBytes; }

  private long accountedBytesForCapacity(int required) {
    long longBytes = previousRows.accountedBytesForCapacity(required);
    long intBytes = deleted.accountedBytesForCapacity(required);
    if (longBytes < 0 || intBytes < 0 || intBytes > (Long.MAX_VALUE - longBytes) / 3) {
      return -1;
    }
    return longBytes + intBytes * 3;
  }
}
