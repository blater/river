package io.riverdb.engine;

import io.riverdb.engine.sql.SqlPreparedPlan;

/** Reusable physical growth unit for session-owned prepared statements. */
final class PreparedStatementChunk {
  static final int SLOT_COUNT = 64;
  // Conservative 64-bit-reference array storage plus object/array headers.
  static final long ACCOUNTED_BYTES = 256L
      + SLOT_COUNT * (Long.BYTES * 3L + Integer.BYTES * 2L + Byte.BYTES);

  private final SqlPreparedPlan[] plans = new SqlPreparedPlan[SLOT_COUNT];
  private final long[] handles = new long[SLOT_COUNT];
  private final long[] retainedBytes = new long[SLOT_COUNT];
  private final boolean[] queries = new boolean[SLOT_COUNT];
  private final int[] programReferences = new int[SLOT_COUNT];
  private final int[] nextFree = new int[SLOT_COUNT];
  private int used;

  void open(
      int slot, long handle, SqlPreparedPlan plan,
      boolean query, long bytes) {
    plans[slot] = plan;
    handles[slot] = handle;
    retainedBytes[slot] = bytes;
    queries[slot] = query;
    used++;
  }

  SqlPreparedPlan resolve(int slot, long handle, boolean query) {
    return active(slot, handle) && queries[slot] == query ? plans[slot] : null;
  }

  long retainedBytes(int slot, long handle) {
    return active(slot, handle) ? retainedBytes[slot] : 0;
  }

  boolean close(int slot, long handle) {
    if (!active(slot, handle) || programReferences[slot] != 0) return false;
    clear(slot);
    return true;
  }

  boolean canClose(int slot, long handle) {
    return active(slot, handle) && programReferences[slot] == 0;
  }

  SqlPreparedPlan retain(int slot, long handle) {
    if (!active(slot, handle) || programReferences[slot] == Integer.MAX_VALUE) return null;
    programReferences[slot]++;
    return plans[slot];
  }

  boolean release(int slot, long handle) {
    if (!active(slot, handle) || programReferences[slot] == 0) return false;
    programReferences[slot]--;
    return true;
  }

  long activeRetainedBytes() {
    long bytes = 0;
    for (int slot = 0; slot < SLOT_COUNT; slot++) bytes += retainedBytes[slot];
    return bytes;
  }

  void clear() {
    for (int slot = 0; slot < SLOT_COUNT; slot++) clear(slot);
  }

  int nextFree(int slot) { return nextFree[slot]; }

  void nextFree(int slot, int encodedSlot) { nextFree[slot] = encodedSlot; }

  private boolean active(int slot, long handle) {
    return slot >= 0 && slot < SLOT_COUNT
        && handles[slot] == handle && plans[slot] != null;
  }

  private void clear(int slot) {
    if (plans[slot] == null) return;
    plans[slot] = null;
    handles[slot] = retainedBytes[slot] = 0;
    queries[slot] = false;
    programReferences[slot] = 0;
    used--;
  }
}
