package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;

/** Pre-reserved primitive key-to-ordinal index for one caller-owned journal. */
final class IndexedLongOrdinalIndex {
  private final int maximumEntries;
  private long[] keys = new long[0];
  private int[] ordinals = new int[0];
  private int[] occupiedSlots = new int[0];
  private int count;

  IndexedLongOrdinalIndex(int maximum) {
    if (maximum < 0) throw new IllegalArgumentException("negative index capacity");
    maximumEntries = maximum;
  }

  StatusCode reserve(int entries) {
    if (entries < count || entries > maximumEntries) return StatusCode.RESOURCE_EXHAUSTED;
    int required = tableCapacity(entries);
    if (required < 0) return StatusCode.RESOURCE_EXHAUSTED;
    if (required <= ordinals.length) return StatusCode.OK;
    try {
      long[] nextKeys = new long[required];
      int[] nextOrdinals = new int[required];
      int[] nextOccupiedSlots = new int[required];
      for (int index = 0; index < count; index++) {
        int oldSlot = occupiedSlots[index];
        nextOccupiedSlots[index] = put(
            nextKeys, nextOrdinals, keys[oldSlot], ordinals[oldSlot] - 1);
      }
      keys = nextKeys;
      ordinals = nextOrdinals;
      occupiedSlots = nextOccupiedSlots;
      return StatusCode.OK;
    } catch (OutOfMemoryError failure) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  int find(long key) {
    if (key <= 0 || ordinals.length == 0) return -1;
    int slot = slot(key, ordinals.length);
    for (int probes = 0; probes < ordinals.length; probes++) {
      int ordinal = ordinals[slot];
      if (ordinal == 0) return -1;
      if (keys[slot] == key) return ordinal - 1;
      if (++slot == ordinals.length) slot = 0;
    }
    return -1;
  }

  boolean add(long key, int ordinal) {
    if (key <= 0 || ordinal < 0 || count >= maximumEntries
        || count >= ordinals.length || find(key) >= 0) return false;
    occupiedSlots[count] = put(keys, ordinals, key, ordinal);
    count++;
    return true;
  }

  void clear() {
    for (int index = 0; index < count; index++) ordinals[occupiedSlots[index]] = 0;
    count = 0;
  }

  void release() {
    keys = new long[0];
    ordinals = new int[0];
    occupiedSlots = new int[0];
    count = 0;
  }

  long accountedBytes() {
    return 3L * 16L + (long) keys.length * Long.BYTES
        + 2L * ordinals.length * Integer.BYTES + 32L;
  }

  long accountedBytesForEntries(int entries) {
    int capacity = tableCapacity(entries);
    return capacity < 0 ? -1 : 3L * 16L + (long) capacity * 16L + 32L;
  }

  private int tableCapacity(int entries) {
    if (entries == 0) return 0;
    int required = entries;
    if (entries <= 1 << 29) {
      required = 4;
      int target = entries << 1;
      while (required < target) required <<= 1;
    }
    return Math.max(ordinals.length, required);
  }

  private static int put(
      long[] targetKeys, int[] targetOrdinals, long key, int ordinal) {
    int slot = slot(key, targetOrdinals.length);
    while (targetOrdinals[slot] != 0) {
      if (++slot == targetOrdinals.length) slot = 0;
    }
    targetKeys[slot] = key;
    targetOrdinals[slot] = ordinal + 1;
    return slot;
  }

  private static int slot(long key, int capacity) {
    long mixed = key ^ key >>> 33;
    mixed *= 0xff51afd7ed558ccdl;
    mixed ^= mixed >>> 33;
    return (capacity & capacity - 1) == 0
        ? (int) mixed & capacity - 1
        : (int) Long.remainderUnsigned(mixed, capacity);
  }
}
