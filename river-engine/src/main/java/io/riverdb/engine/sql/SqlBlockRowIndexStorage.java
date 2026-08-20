package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;

/** Proportional primitive storage for block row offsets and sort metadata. */
final class SqlBlockRowIndexStorage {
  private static final int WARM_ROWS = 1_024;
  private long[] offsets;
  private int[] lengths;
  private int[] order;
  private long[] keys;
  private boolean[] keyNulls;
  private int[] textOffsets;
  private short[] textLengths;

  StatusCode ensure(
      int required, boolean sorted, boolean textKey, long otherRetained) {
    if (offsets != null && offsets.length >= required
        && (!sorted || keys != null && keys.length >= required)
        && (!textKey || textOffsets != null && textOffsets.length >= required)) {
      return StatusCode.OK;
    }
    int capacity = offsets == null ? WARM_ROWS : offsets.length;
    while (capacity < required) capacity = Math.min(SqlBlockRowStore.MAXIMUM_ROWS, capacity * 2);
    if (otherRetained + prospectiveBytes(capacity, sorted, textKey)
        > SqlBlockRowStore.MAXIMUM_BYTES) return StatusCode.RESOURCE_EXHAUSTED;
    offsets = grow(offsets, capacity);
    lengths = grow(lengths, capacity);
    order = grow(order, capacity);
    if (sorted) {
      keys = grow(keys, capacity);
      keyNulls = grow(keyNulls, capacity);
    }
    if (textKey) {
      textOffsets = grow(textOffsets, capacity);
      textLengths = grow(textLengths, capacity);
    }
    return StatusCode.OK;
  }

  void setRecord(int ordinal, long offset, int length) {
    offsets[ordinal] = offset;
    lengths[ordinal] = length;
    order[ordinal] = ordinal;
  }
  long offset(int ordinal) { return offsets[ordinal]; }
  int length(int ordinal) { return lengths[ordinal]; }
  int order(int position) { return order[position]; }
  void order(int position, int ordinal) { order[position] = ordinal; }
  long key(int ordinal) { return keys[ordinal]; }
  void key(int ordinal, long value) { keys[ordinal] = value; }
  boolean keyNull(int ordinal) { return keyNulls[ordinal]; }
  void keyNull(int ordinal, boolean value) { keyNulls[ordinal] = value; }
  int textOffset(int ordinal) { return textOffsets[ordinal]; }
  int textLength(int ordinal) { return Short.toUnsignedInt(textLengths[ordinal]); }
  void text(int ordinal, int offset, int length) {
    textOffsets[ordinal] = offset;
    textLengths[ordinal] = (short) length;
  }

  long retainedBytes() {
    long retained = offsets == null ? 0
        : (long) offsets.length * Long.BYTES
            + (long) lengths.length * Integer.BYTES
            + (long) order.length * Integer.BYTES;
    if (keys != null) retained += (long) keys.length * Long.BYTES;
    if (keyNulls != null) retained += keyNulls.length;
    if (textOffsets != null) retained += (long) textOffsets.length * Integer.BYTES;
    if (textLengths != null) retained += (long) textLengths.length * Short.BYTES;
    return retained;
  }

  void eraseSlot(int index) {
    offsets[index] = 0;
    lengths[index] = 0;
    order[index] = 0;
    if (keys != null && index < keys.length) keys[index] = 0;
    if (keyNulls != null && index < keyNulls.length) keyNulls[index] = false;
    if (textOffsets != null && index < textOffsets.length) textOffsets[index] = 0;
    if (textLengths != null && index < textLengths.length) textLengths[index] = 0;
  }

  void close(int used) {
    for (int index = 0; index < used; index++) eraseSlot(index);
    if (offsets != null && offsets.length > WARM_ROWS) {
      offsets = null;
      lengths = null;
      order = null;
    }
    if (keys != null && keys.length > WARM_ROWS) keys = null;
    if (keyNulls != null && keyNulls.length > WARM_ROWS) keyNulls = null;
    if (textOffsets != null && textOffsets.length > WARM_ROWS) textOffsets = null;
    if (textLengths != null && textLengths.length > WARM_ROWS) textLengths = null;
  }

  private long prospectiveBytes(int capacity, boolean sorted, boolean textKey) {
    long retained = (long) capacity * (Long.BYTES + Integer.BYTES + Integer.BYTES);
    retained += sorted ? (long) capacity * Long.BYTES + capacity
        : (keys == null ? 0 : (long) keys.length * Long.BYTES)
            + (keyNulls == null ? 0 : keyNulls.length);
    return retained + (textKey ? (long) capacity * (Integer.BYTES + Short.BYTES)
        : (textOffsets == null ? 0 : (long) textOffsets.length * Integer.BYTES)
            + (textLengths == null ? 0 : (long) textLengths.length * Short.BYTES));
  }

  private static long[] grow(long[] source, int capacity) {
    long[] grown = new long[capacity];
    if (source != null) {
      System.arraycopy(source, 0, grown, 0, source.length);
      java.util.Arrays.fill(source, 0);
    }
    return grown;
  }
  private static int[] grow(int[] source, int capacity) {
    int[] grown = new int[capacity];
    if (source != null) {
      System.arraycopy(source, 0, grown, 0, source.length);
      java.util.Arrays.fill(source, 0);
    }
    return grown;
  }
  private static short[] grow(short[] source, int capacity) {
    short[] grown = new short[capacity];
    if (source != null) {
      System.arraycopy(source, 0, grown, 0, source.length);
      java.util.Arrays.fill(source, (short) 0);
    }
    return grown;
  }
  private static boolean[] grow(boolean[] source, int capacity) {
    boolean[] grown = new boolean[capacity];
    if (source != null) {
      System.arraycopy(source, 0, grown, 0, source.length);
      java.util.Arrays.fill(source, false);
    }
    return grown;
  }
}
