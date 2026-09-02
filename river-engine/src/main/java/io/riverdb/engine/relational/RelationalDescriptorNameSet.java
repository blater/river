package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;

/** Actual-count primitive hash slots used only while validating committed catalog names. */
final class RelationalDescriptorNameSet {
  private static final int INITIAL_CAPACITY = 16;
  private final RelationalDescriptorNameArrayAllocator allocator;
  private long[] hashes = new long[0];
  private long[] objectIds = new long[0];
  private int count;

  RelationalDescriptorNameSet() {
    this(RelationalDescriptorNameArrayAllocator.STANDARD);
  }

  RelationalDescriptorNameSet(RelationalDescriptorNameArrayAllocator arrayAllocator) {
    allocator = arrayAllocator;
  }

  void reset() {
    for (int index = 0; index < objectIds.length; index++) objectIds[index] = 0;
    count = 0;
  }

  StatusCode reserveInsert() {
    if (count < objectIds.length / 2) return StatusCode.OK;
    int capacity = objectIds.length == 0 ? INITIAL_CAPACITY : objectIds.length << 1;
    if (capacity <= objectIds.length) return StatusCode.RESOURCE_EXHAUSTED;
    long[] grownHashes;
    long[] grownObjectIds;
    try {
      grownHashes = allocator.longs(capacity);
      grownObjectIds = allocator.longs(capacity);
    } catch (OutOfMemoryError exhausted) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    for (int index = 0; index < objectIds.length; index++) {
      if (objectIds[index] != 0) {
        int target = vacant(grownObjectIds, hashes[index]);
        grownHashes[target] = hashes[index];
        grownObjectIds[target] = objectIds[index];
      }
    }
    hashes = grownHashes;
    objectIds = grownObjectIds;
    return StatusCode.OK;
  }

  int first(long hash) {
    return slot(hash, objectIds.length);
  }

  int next(int slot) {
    return slot + 1 == objectIds.length ? 0 : slot + 1;
  }

  long objectIdAt(int slot) {
    return objectIds[slot];
  }

  long hashAt(int slot) {
    return hashes[slot];
  }

  void insert(int slot, long hash, long objectId) {
    hashes[slot] = hash;
    objectIds[slot] = objectId;
    count++;
  }

  int capacity() {
    return objectIds.length;
  }

  int count() {
    return count;
  }

  private static int vacant(long[] ids, long hash) {
    int index = slot(hash, ids.length);
    while (ids[index] != 0) index = index + 1 == ids.length ? 0 : index + 1;
    return index;
  }

  private static int slot(long hash, int capacity) {
    return (int) (hash ^ hash >>> 32) & capacity - 1;
  }
}
