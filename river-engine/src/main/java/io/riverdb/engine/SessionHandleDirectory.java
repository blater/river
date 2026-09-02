package io.riverdb.engine;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.sql.SqlRetainedBudget;
import java.util.concurrent.atomic.AtomicLong;

/** Session-local O(1) lookup for process-unique opaque resource handles. */
final class SessionHandleDirectory {
  private static final long EMPTY = 0;
  private static final long DELETED = -1;
  private static final long HEADER_BYTES = 64;
  private static final AtomicLong NEXT_HANDLE = new AtomicLong(1);
  private final SqlRetainedBudget budget;
  private long[] handles = new long[0];
  private int[] resources = new int[0];
  private int size;
  private int deleted;
  private long retainedBytes;

  SessionHandleDirectory(SqlRetainedBudget retainedBudget) { budget = retainedBudget; }

  long add(int resource) {
    if (resource == 0) return 0;
    if ((size + deleted + 1L) * 3 >= (long) handles.length * 2) {
      int capacity = size * 3 < handles.length && handles.length != 0
          ? handles.length : growth(handles.length);
      if (capacity <= 0 || !resize(capacity).isOk()) return 0;
    }
    long handle = NEXT_HANDLE.getAndIncrement();
    if (handle <= 0) return 0;
    int slot = insertionSlot(handle);
    if (handles[slot] == DELETED) deleted--;
    handles[slot] = handle;
    resources[slot] = resource;
    size++;
    return handle;
  }

  int resolve(long handle) {
    int slot = find(handle);
    return slot < 0 ? 0 : resources[slot];
  }

  boolean remove(long handle) {
    int slot = find(handle);
    if (slot < 0) return false;
    handles[slot] = DELETED;
    resources[slot] = 0;
    size--;
    deleted++;
    return true;
  }

  StatusCode clear() {
    StatusCode status = retainedBytes == 0
        ? StatusCode.OK : budget.releaseRetainedBytes(retainedBytes);
    if (!status.isOk()) return status;
    handles = new long[0];
    resources = new int[0];
    size = deleted = 0;
    retainedBytes = 0;
    return StatusCode.OK;
  }

  private StatusCode resize(int capacity) {
    long bytes = HEADER_BYTES + (long) capacity * (Long.BYTES + Integer.BYTES);
    long added = bytes - retainedBytes;
    if (added < 0) return StatusCode.INVARIANT_BROKEN;
    StatusCode status = added == 0
        ? StatusCode.OK : budget.reserveRetainedBytes(added);
    if (!status.isOk()) return status;
    try {
      long[] nextHandles = new long[capacity];
      int[] nextResources = new int[capacity];
      for (int slot = 0; slot < handles.length; slot++) {
        long handle = handles[slot];
        if (handle <= 0) continue;
        int target = insertionSlot(handle, nextHandles);
        nextHandles[target] = handle;
        nextResources[target] = resources[slot];
      }
      handles = nextHandles;
      resources = nextResources;
      deleted = 0;
      retainedBytes = bytes;
      return StatusCode.OK;
    } catch (OutOfMemoryError failure) {
      StatusCode cleanup = added == 0
          ? StatusCode.OK : budget.releaseRetainedBytes(added);
      return cleanup.isOk() ? StatusCode.RESOURCE_EXHAUSTED : cleanup;
    }
  }

  private int find(long handle) {
    if (handle <= 0 || handles.length == 0) return -1;
    int slot = hash(handle) & handles.length - 1;
    while (handles[slot] != EMPTY) {
      if (handles[slot] == handle) return slot;
      slot = slot + 1 & handles.length - 1;
    }
    return -1;
  }

  private int insertionSlot(long handle) { return insertionSlot(handle, handles); }
  private static int insertionSlot(long handle, long[] target) {
    int slot = hash(handle) & target.length - 1;
    int deletedSlot = -1;
    while (target[slot] != EMPTY) {
      if (target[slot] == DELETED && deletedSlot < 0) deletedSlot = slot;
      slot = slot + 1 & target.length - 1;
    }
    return deletedSlot < 0 ? slot : deletedSlot;
  }

  private static int growth(int current) {
    if (current == 0) return 16;
    int next = current << 1;
    return next > current ? next : -1;
  }

  private static int hash(long value) {
    value ^= value >>> 33;
    value *= 0xff51afd7ed558ccdl;
    value ^= value >>> 33;
    return (int) (value ^ value >>> 32);
  }
}
