package io.riverdb.engine.runtime.materialized;

import java.util.Arrays;

/** Bounded allocation-free open-addressed index for two-long keys. */
final class SqlMaterializedLongPairIndex {
  private static final int EMPTY = -1;

  private final long[] first;
  private final long[] second;
  private final int[] values;
  private final int mask;

  SqlMaterializedLongPairIndex(int capacity) {
    first = new long[capacity];
    second = new long[capacity];
    values = new int[capacity];
    Arrays.fill(values, EMPTY);
    mask = capacity - 1;
  }

  static int capacity(int entries, int scale) {
    if (entries <= 0 || scale <= 0) return -1;
    long needed = (long) entries * scale;
    if (needed > 1L << 30) return -1;
    int capacity = 1;
    while (capacity < needed) capacity <<= 1;
    return capacity;
  }

  int find(long left, long right) {
    int slot = hash(left, right) & mask;
    for (int probes = 0; probes < values.length; probes++) {
      int value = values[slot];
      if (value == EMPTY) return EMPTY;
      if (first[slot] == left && second[slot] == right) return value;
      slot = slot + 1 & mask;
    }
    return EMPTY;
  }

  boolean put(long left, long right, int value) {
    int slot = hash(left, right) & mask;
    for (int probes = 0; probes < values.length; probes++) {
      if (values[slot] == EMPTY || first[slot] == left && second[slot] == right) {
        first[slot] = left;
        second[slot] = right;
        values[slot] = value;
        return true;
      }
      slot = slot + 1 & mask;
    }
    return false;
  }

  boolean remove(long left, long right) {
    int slot = slot(left, right);
    if (slot < 0) return false;
    values[slot] = EMPTY;
    int next = slot + 1 & mask;
    while (values[next] != EMPTY) {
      long displacedFirst = first[next];
      long displacedSecond = second[next];
      int displacedValue = values[next];
      values[next] = EMPTY;
      put(displacedFirst, displacedSecond, displacedValue);
      next = next + 1 & mask;
    }
    return true;
  }

  private int slot(long left, long right) {
    int slot = hash(left, right) & mask;
    for (int probes = 0; probes < values.length; probes++) {
      if (values[slot] == EMPTY) return EMPTY;
      if (first[slot] == left && second[slot] == right) return slot;
      slot = slot + 1 & mask;
    }
    return EMPTY;
  }

  private static int hash(long left, long right) {
    long mixed = left * 0x9e3779b97f4a7c15L + Long.rotateLeft(right, 27);
    mixed ^= mixed >>> 33;
    mixed *= 0xff51afd7ed558ccdL;
    mixed ^= mixed >>> 33;
    return (int) mixed;
  }
}
