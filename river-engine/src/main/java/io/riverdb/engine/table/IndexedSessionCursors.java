package io.riverdb.engine.table;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;

/** Owns the bounded cursor registry retained by one transaction session. */
final class IndexedSessionCursors {
  private static final int MAXIMUM_ACTIVE = SqlShapeLimits.MAX_ACTIVE_QUERY_SCANS;

  private IndexedScanCursor[] scalar = new IndexedScanCursor[8];
  private IndexedTupleScanCursor[] tuples = new IndexedTupleScanCursor[8];
  private int scalarCount;
  private int tupleCount;

  int scalarCount() { return scalarCount; }

  boolean active() { return scalarCount + tupleCount != 0; }

  StatusCode reserveScalar() {
    if (full()) return StatusCode.RESOURCE_EXHAUSTED;
    if (scalarCount < scalar.length) return StatusCode.OK;
    int capacity = BoundedArrayGrowth.capacity(
        scalar.length, scalarCount + 1, MAXIMUM_ACTIVE, 8);
    if (capacity < 0) return StatusCode.RESOURCE_EXHAUSTED;
    try {
      IndexedScanCursor[] next = new IndexedScanCursor[capacity];
      System.arraycopy(scalar, 0, next, 0, scalarCount);
      scalar = next;
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  StatusCode reserveTuple() {
    if (full()) return StatusCode.RESOURCE_EXHAUSTED;
    if (tupleCount < tuples.length) return StatusCode.OK;
    int capacity = BoundedArrayGrowth.capacity(
        tuples.length, tupleCount + 1, MAXIMUM_ACTIVE, 8);
    if (capacity < 0) return StatusCode.RESOURCE_EXHAUSTED;
    try {
      IndexedTupleScanCursor[] next = new IndexedTupleScanCursor[capacity];
      System.arraycopy(tuples, 0, next, 0, tupleCount);
      tuples = next;
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  void add(IndexedScanCursor cursor) { scalar[scalarCount++] = cursor; }

  void add(IndexedTupleScanCursor cursor) { tuples[tupleCount++] = cursor; }

  void removeScalar(int index) {
    scalarCount--;
    scalar[index] = scalar[scalarCount];
    scalar[scalarCount] = null;
  }

  void removeTuple(int index) {
    tupleCount--;
    tuples[index] = tuples[tupleCount];
    tuples[tupleCount] = null;
  }

  int find(IndexedScanCursor cursor) {
    for (int index = 0; index < scalarCount; index++) {
      if (scalar[index] == cursor) return index;
    }
    return -1;
  }

  int find(IndexedTupleScanCursor cursor) {
    if (cursor == null) return -1;
    for (int index = 0; index < tupleCount; index++) {
      if (tuples[index] == cursor) return index;
    }
    return -1;
  }

  private boolean full() { return scalarCount + tupleCount >= MAXIMUM_ACTIVE; }
}
