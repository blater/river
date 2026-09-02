package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.catalog.CatalogKeyspace;

/** Reusable transaction journal of maximum next logical row IDs by table. */
final class IndexedLogicalRowIdFloors {
  private final int maximumEntries;
  private final IndexedLongOrdinalIndex ordinalByObject;
  private final IndexedLongChunks objectIds;
  private final IndexedLongChunks nextLogicalRowIds;
  private int count;

  IndexedLogicalRowIdFloors(int maximumPendingMutations) {
    if (maximumPendingMutations < 0) {
      throw new IllegalArgumentException("negative logical row floor capacity");
    }
    maximumEntries = maximumPendingMutations;
    ordinalByObject = new IndexedLongOrdinalIndex(maximumPendingMutations);
    objectIds = new IndexedLongChunks(maximumPendingMutations);
    nextLogicalRowIds = new IndexedLongChunks(maximumPendingMutations);
  }

  StatusCode record(long objectId, long nextLogicalRowId) {
    if (!CatalogKeyspace.validObjectHead(objectId) || nextLogicalRowId <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int ordinal = ordinalByObject.find(objectId);
    if (ordinal >= 0) {
      if (nextLogicalRowId > nextLogicalRowIds.get(ordinal)) {
        nextLogicalRowIds.set(ordinal, nextLogicalRowId);
      }
      return StatusCode.OK;
    }
    if (count >= maximumEntries) return StatusCode.RESOURCE_EXHAUSTED;
    StatusCode status = ordinalByObject.reserve(count + 1);
    if (status.isOk()) status = objectIds.reserve(count + 1);
    if (status.isOk()) status = nextLogicalRowIds.reserve(count + 1);
    if (!status.isOk()) return status;
    if (!ordinalByObject.add(objectId, count)) return StatusCode.INVARIANT_BROKEN;
    objectIds.set(count, objectId);
    nextLogicalRowIds.set(count, nextLogicalRowId);
    count++;
    return StatusCode.OK;
  }

  int count() {
    return count;
  }

  long objectIdAt(int index) {
    return objectIds.get(index);
  }

  long nextAt(int index) {
    return nextLogicalRowIds.get(index);
  }

  void reset() {
    ordinalByObject.clear();
    count = 0;
  }

  void release() {
    ordinalByObject.release();
    objectIds.release();
    nextLogicalRowIds.release();
    count = 0;
  }

  long accountedBytes() {
    return ordinalByObject.accountedBytes()
        + objectIds.allocatedBytes() + nextLogicalRowIds.allocatedBytes();
  }
}
