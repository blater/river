package io.riverdb.engine;

import io.riverdb.engine.sql.SqlPreparedPlan;

/** Reusable physical growth unit for session-owned prepared statements. */
final class PreparedStatementChunk {
  static final int SLOT_COUNT = 64;
  // Conservative 64-bit-reference array storage plus object/array headers.
  static final long ACCOUNTED_BYTES = 256L
      + SLOT_COUNT * (Long.BYTES * 2L + Integer.BYTES);

  private final RetainedPreparedTemplate[] templates =
      new RetainedPreparedTemplate[SLOT_COUNT];
  private final long[] handles = new long[SLOT_COUNT];
  private final int[] nextFree = new int[SLOT_COUNT];

  void open(int slot, long handle, RetainedPreparedTemplate template) {
    templates[slot] = template;
    handles[slot] = handle;
  }

  SqlPreparedPlan resolve(int slot, long handle, boolean query) {
    SqlPreparedPlan plan = resolve(slot, handle);
    return plan != null && plan.query() == query ? plan : null;
  }

  SqlPreparedPlan resolve(int slot, long handle) {
    return active(slot, handle) ? templates[slot].plan : null;
  }

  RetainedPreparedTemplate template(int slot, long handle) {
    return active(slot, handle) ? templates[slot] : null;
  }

  boolean close(int slot, long handle) {
    if (!active(slot, handle)) return false;
    clear(slot);
    return true;
  }

  void clear() {
    for (int slot = 0; slot < SLOT_COUNT; slot++) clear(slot);
  }

  int nextFree(int slot) { return nextFree[slot]; }

  void nextFree(int slot, int encodedSlot) { nextFree[slot] = encodedSlot; }

  private boolean active(int slot, long handle) {
    return slot >= 0 && slot < SLOT_COUNT
        && handles[slot] == handle && templates[slot] != null;
  }

  private void clear(int slot) {
    if (templates[slot] == null) return;
    templates[slot] = null;
    handles[slot] = 0;
  }
}
