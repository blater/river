package io.riverdb.engine.table;

import java.util.Arrays;

/** Open-addressed primitive index of the latest entry for an exact tuple intent key. */
final class IndexedTupleIntentKeyIndex {
  private static final int INITIAL_CAPACITY = 16;
  private int[][] entries = new int[0][];

  void reserve(int values) {
    int needed = capacityFor(values);
    if (needed <= capacity()) return;
    int[][] next = new int[IndexedTupleIntentColumns.chunksFor(needed)][];
    for (int index = 0; index < next.length; index++) {
      next[index] = new int[IndexedTupleIntentColumns.SIZE]; Arrays.fill(next[index], -1);
    }
    entries = next;
  }
  int capacity() { return entries.length * IndexedTupleIntentColumns.SIZE; }
  int mask() { return capacity() - 1; }
  int get(int index) { return entries[index >> IndexedTupleIntentColumns.SHIFT][index & IndexedTupleIntentColumns.MASK]; }
  void set(int index, int value) { entries[index >> IndexedTupleIntentColumns.SHIFT][index & IndexedTupleIntentColumns.MASK] = value; }
  void clear() { for (int index = 0; index < capacity(); index++) set(index, -1); }
  void release() { entries = new int[0][]; }
  static int capacityFor(int values) {
    if (values <= 0) return 0;
    long target = Math.max(INITIAL_CAPACITY, (long) values * 2L);
    if (target > (1L << 30)) return -1;
    int capacity = INITIAL_CAPACITY;
    while (capacity < target) capacity <<= 1;
    return capacity;
  }
}
