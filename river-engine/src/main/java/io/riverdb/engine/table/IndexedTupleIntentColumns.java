package io.riverdb.engine.table;

import java.util.Arrays;

/** Chunked primitive columns for tuple intent metadata. */
final class IndexedTupleIntentColumns {
  static final int SHIFT = 8;
  static final int SIZE = 1 << SHIFT;
  static final int MASK = SIZE - 1;

  private int[][] operations = new int[0][];
  private int[][] rawOperations = new int[0][];
  private int[][] descriptors = new int[0][];
  private int[][] offsets = new int[0][];
  private int[][] lengths = new int[0][];
  private int[][] valueOffsets = new int[0][];
  private int[][] valueLengths = new int[0][];
  private int[][] firstEntries = new int[0][];
  private int[][] keyHashes = new int[0][];
  private long[][] logicalRowIds = new long[0][];
  private byte[][] active = new byte[0][];

  void reserve(int required) {
    int needed = chunksFor(required);
    if (needed <= operations.length) return;
    operations = growInts(operations, needed);
    rawOperations = growInts(rawOperations, needed);
    descriptors = growInts(descriptors, needed);
    offsets = growInts(offsets, needed); lengths = growInts(lengths, needed);
    valueOffsets = growInts(valueOffsets, needed);
    valueLengths = growInts(valueLengths, needed);
    firstEntries = growInts(firstEntries, needed); keyHashes = growInts(keyHashes, needed);
    logicalRowIds = growLongs(logicalRowIds, needed); active = growBytes(active, needed);
  }

  boolean hasSlot(int index, int maximum) {
    return index >= 0 && index < maximum && index < operations.length * SIZE;
  }
  int chunks() { return operations.length; }
  int operation(int i) { return operations[i >> SHIFT][i & MASK]; }
  int rawOperation(int i) { return rawOperations[i >> SHIFT][i & MASK]; }
  int descriptor(int i) { return descriptors[i >> SHIFT][i & MASK]; }
  int offset(int i) { return offsets[i >> SHIFT][i & MASK]; }
  int length(int i) { return lengths[i >> SHIFT][i & MASK]; }
  int valueOffset(int i) { return valueOffsets[i >> SHIFT][i & MASK]; }
  int valueLength(int i) { return valueLengths[i >> SHIFT][i & MASK]; }
  int first(int i) { return firstEntries[i >> SHIFT][i & MASK]; }
  int hash(int i) { return keyHashes[i >> SHIFT][i & MASK]; }
  long rowId(int i) { return logicalRowIds[i >> SHIFT][i & MASK]; }
  boolean active(int i) { return active[i >> SHIFT][i & MASK] != 0; }
  void operation(int i, int v) { operations[i >> SHIFT][i & MASK] = v; }
  void rawOperation(int i, int v) { rawOperations[i >> SHIFT][i & MASK] = v; }
  void descriptor(int i, int v) { descriptors[i >> SHIFT][i & MASK] = v; }
  void offset(int i, int v) { offsets[i >> SHIFT][i & MASK] = v; }
  void length(int i, int v) { lengths[i >> SHIFT][i & MASK] = v; }
  void valueOffset(int i, int v) { valueOffsets[i >> SHIFT][i & MASK] = v; }
  void valueLength(int i, int v) { valueLengths[i >> SHIFT][i & MASK] = v; }
  void first(int i, int v) { firstEntries[i >> SHIFT][i & MASK] = v; }
  void hash(int i, int v) { keyHashes[i >> SHIFT][i & MASK] = v; }
  void rowId(int i, long v) { logicalRowIds[i >> SHIFT][i & MASK] = v; }
  void active(int i, boolean v) { active[i >> SHIFT][i & MASK] = v ? (byte) 1 : 0; }

  void clear(int from, int to) {
    for (int index = from; index < to; index++) {
      operation(index, 0); rawOperation(index, 0);
      descriptor(index, 0); rowId(index, 0); offset(index, 0);
      length(index, 0); valueOffset(index, 0); valueLength(index, 0);
      first(index, 0); hash(index, 0); active(index, false);
    }
  }

  void release() {
    operations = rawOperations = descriptors = offsets = lengths = valueOffsets = valueLengths =
        firstEntries = keyHashes = new int[0][];
    logicalRowIds = new long[0][]; active = new byte[0][];
  }

  static int chunksFor(int values) {
    if (values < 0 || values > Integer.MAX_VALUE - MASK) return -1;
    return (values + MASK) >> SHIFT;
  }
  private static int[][] growInts(int[][] source, int needed) {
    int[][] next = Arrays.copyOf(source, needed);
    for (int index = source.length; index < needed; index++) next[index] = new int[SIZE];
    return next;
  }
  private static long[][] growLongs(long[][] source, int needed) {
    long[][] next = Arrays.copyOf(source, needed);
    for (int index = source.length; index < needed; index++) next[index] = new long[SIZE];
    return next;
  }
  private static byte[][] growBytes(byte[][] source, int needed) {
    byte[][] next = Arrays.copyOf(source, needed);
    for (int index = source.length; index < needed; index++) next[index] = new byte[SIZE];
    return next;
  }
}
