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
  private long generation = 1;

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
        changeGeneration();
      }
      return StatusCode.OK;
    }
    StatusCode status = reserve(count + 1);
    if (!status.isOk()) return status;
    if (!ordinalByObject.add(objectId, count)) return StatusCode.INVARIANT_BROKEN;
    objectIds.set(count, objectId);
    nextLogicalRowIds.set(count, nextLogicalRowId);
    count++;
    changeGeneration();
    return StatusCode.OK;
  }

  int count() {
    return count;
  }

  StatusCode reserve(int required) {
    if (required < count || required > maximumEntries) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = ordinalByObject.reserve(required);
    if (status.isOk()) status = objectIds.reserve(required);
    if (status.isOk()) status = nextLogicalRowIds.reserve(required);
    return status;
  }

  int countAfterRecord(long objectId) {
    if (!CatalogKeyspace.validObjectHead(objectId)) return -1;
    if (ordinalByObject.find(objectId) >= 0) return count;
    return count == maximumEntries ? -1 : count + 1;
  }

  long objectIdAt(int index) {
    return objectIds.get(index);
  }

  long nextAt(int index) {
    return nextLogicalRowIds.get(index);
  }

  long generation() { return generation; }

  void reset() {
    boolean changed = count != 0;
    ordinalByObject.clear();
    count = 0;
    if (changed) changeGeneration();
  }

  void release() {
    boolean changed = count != 0;
    ordinalByObject.release();
    objectIds.release();
    nextLogicalRowIds.release();
    count = 0;
    if (changed) changeGeneration();
  }

  long accountedBytes() {
    return ordinalByObject.accountedBytes()
        + objectIds.allocatedBytes() + nextLogicalRowIds.allocatedBytes();
  }

  long accountedBytesForRecord(long objectId) {
    if (!CatalogKeyspace.validObjectHead(objectId)) return -1;
    int required = ordinalByObject.find(objectId) >= 0 ? count : count + 1;
    if (required < count || required > maximumEntries) return -1;
    long indexBytes = ordinalByObject.accountedBytesForEntries(required);
    long objectBytes = Math.max(
        objectIds.allocatedBytes(), objectIds.accountedBytesForCapacity(required));
    long nextBytes = Math.max(
        nextLogicalRowIds.allocatedBytes(),
        nextLogicalRowIds.accountedBytesForCapacity(required));
    if (indexBytes < 0 || objectBytes < 0 || nextBytes < 0
        || indexBytes > Long.MAX_VALUE - objectBytes
        || indexBytes + objectBytes > Long.MAX_VALUE - nextBytes) return -1;
    return indexBytes + objectBytes + nextBytes;
  }

  long accountedBytesForEntries(int entries) {
    if (entries < count || entries > maximumEntries) return -1;
    long indexBytes = ordinalByObject.accountedBytesForEntries(entries);
    long objectBytes = Math.max(
        objectIds.allocatedBytes(), objectIds.accountedBytesForCapacity(entries));
    long nextBytes = Math.max(
        nextLogicalRowIds.allocatedBytes(),
        nextLogicalRowIds.accountedBytesForCapacity(entries));
    if (indexBytes < 0 || objectBytes < 0 || nextBytes < 0
        || indexBytes > Long.MAX_VALUE - objectBytes
        || indexBytes + objectBytes > Long.MAX_VALUE - nextBytes) return -1;
    return indexBytes + objectBytes + nextBytes;
  }

  private void changeGeneration() {
    generation = generation == Long.MAX_VALUE ? 0 : generation + 1;
  }
}
