package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.runtime.DatabasePageCachePlan;
import io.riverdb.format.page.PageCodec;

/** Bounded metadata for pages participating in active staging or dirty publication. */
final class IndexedPageStateValues {
  private static final int[] DETACHED_INTS = new int[1];
  private static final byte[] DETACHED_BYTES = new byte[1];
  private static final long[] DETACHED_LONGS = new long[1];
  private static final byte STAGED = 1;
  private static final byte DIRTY = 2;

  private final int limit;
  private int mask;
  private int[] pageIds;
  private byte[] flags;
  private long[] starts;
  private long[] ends;
  private int[] kinds;
  private long[] owners;
  private int count;
  private int dirtyCount;

  IndexedPageStateValues(DatabasePageCachePlan config) {
    limit = config.activeMetadataEntries();
    int capacity = config.metadataMapCapacity();
    mask = capacity - 1;
    pageIds = new int[capacity];
    flags = new byte[capacity];
    starts = new long[capacity];
    ends = new long[capacity];
    kinds = new int[capacity];
    owners = new long[capacity];
  }

  boolean staged(int pageId) { return flag(pageId, STAGED); }
  boolean dirty(int pageId) { return flag(pageId, DIRTY); }
  long start(int pageId) { int slot = find(pageId); return slot < 0 ? 0 : starts[slot]; }
  long end(int pageId) { int slot = find(pageId); return slot < 0 ? 0 : ends[slot]; }
  int kind(int pageId) {
    int slot = find(pageId);
    int kind = slot < 0 ? 0 : kinds[slot];
    return kind == 0 ? PageCodec.PAYLOAD_KIND_SCALAR_BTREE : kind;
  }
  long owner(int pageId) { int slot = find(pageId); return slot < 0 ? 0 : owners[slot]; }
  int count() { return count; }
  int capacity() { return limit; }
  boolean hasDirtyPages() { return dirtyCount != 0; }

  StatusCode detach() {
    if (count != 0 || dirtyCount != 0) return StatusCode.CONFLICT;
    abandon();
    return StatusCode.OK;
  }

  void abandon() {
    mask = 0;
    pageIds = kinds = DETACHED_INTS;
    flags = DETACHED_BYTES;
    starts = ends = owners = DETACHED_LONGS;
    count = dirtyCount = 0;
  }

  StatusCode reserve(int pageId) {
    return ensure(pageId) < 0 ? StatusCode.RESOURCE_EXHAUSTED : StatusCode.OK;
  }

  StatusCode identity(int pageId, int kind, long owner) {
    int slot = ensure(pageId);
    if (slot < 0) return StatusCode.RESOURCE_EXHAUSTED;
    kinds[slot] = kind;
    owners[slot] = owner;
    return StatusCode.OK;
  }

  StatusCode changed(int pageId, long start, long end) {
    int slot = ensure(pageId);
    if (slot < 0) return StatusCode.RESOURCE_EXHAUSTED;
    starts[slot] = start;
    ends[slot] = end;
    if ((flags[slot] & DIRTY) == 0) {
      flags[slot] |= DIRTY;
      dirtyCount++;
    }
    return StatusCode.OK;
  }

  void publish(int pageId, int kind, long owner, long start, long end) {
    int slot = find(pageId);
    if (slot < 0) return;
    kinds[slot] = kind;
    owners[slot] = owner;
    starts[slot] = start;
    ends[slot] = end;
    if ((flags[slot] & DIRTY) == 0) {
      flags[slot] |= DIRTY;
      dirtyCount++;
    }
  }

  void clean(int pageId) {
    int slot = find(pageId);
    if (slot < 0) return;
    if ((flags[slot] & DIRTY) != 0) dirtyCount--;
    flags[slot] &= ~DIRTY;
    starts[slot] = 0;
    ends[slot] = 0;
    removeIfInactive(slot);
  }

  void staged(int pageId, boolean value) {
    int slot = value ? ensure(pageId) : find(pageId);
    if (slot < 0) return;
    if (value) flags[slot] |= STAGED;
    else {
      flags[slot] &= ~STAGED;
      removeIfInactive(slot);
    }
  }

  void releaseReservation(int pageId) {
    int slot = find(pageId);
    if (slot >= 0) removeIfInactive(slot);
  }

  private boolean flag(int pageId, byte flag) {
    int slot = find(pageId);
    return slot >= 0 && (flags[slot] & flag) != 0;
  }

  private int ensure(int pageId) {
    int slot = findSlot(pageId);
    if (pageIds[slot] == pageId) return slot;
    if (count >= limit) return -1;
    pageIds[slot] = pageId;
    count++;
    return slot;
  }

  private int find(int pageId) {
    if (pageId <= 0) return -1;
    int slot = findSlot(pageId);
    return pageIds[slot] == pageId ? slot : -1;
  }

  private int findSlot(int pageId) {
    int slot = IndexedPageFrameMap.mix(pageId) & mask;
    while (pageIds[slot] != 0 && pageIds[slot] != pageId) slot = (slot + 1) & mask;
    return slot;
  }

  private void removeIfInactive(int slot) {
    if (flags[slot] != 0) return;
    clearAndRehash(slot);
  }

  private void clearAndRehash(int slot) {
    clear(slot);
    int next = (slot + 1) & mask;
    while (pageIds[next] != 0) {
      int pageId = pageIds[next];
      byte pageFlags = flags[next];
      long start = starts[next];
      long end = ends[next];
      int kind = kinds[next];
      long owner = owners[next];
      clear(next);
      int moved = ensure(pageId);
      flags[moved] = pageFlags;
      starts[moved] = start;
      ends[moved] = end;
      kinds[moved] = kind;
      owners[moved] = owner;
      next = (next + 1) & mask;
    }
  }

  private void clear(int slot) {
    pageIds[slot] = 0;
    flags[slot] = 0;
    starts[slot] = 0;
    ends[slot] = 0;
    kinds[slot] = 0;
    owners[slot] = 0;
    count--;
  }
}
