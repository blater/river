package io.riverdb.sql;

import java.util.Arrays;

/** Geometric flat storage for parsed INSERT rows with wide null/default flags. */
final class SqlInsertRows {
  private long[] values = new long[16];
  private long[] highs = new long[16];
  private int[] descriptors = new int[16];
  private boolean[] nulls = new boolean[16];
  private boolean[] defaults = new boolean[16];
  private int rows;
  private int columns;

  boolean append(
      long[] sourceHighs, long[] sourceValues,
      boolean[] sourceNulls, boolean[] sourceDefaults,
      int[] sourceDescriptors, int count) {
    if (count <= 0 || count > SqlCommand.MAXIMUM_COLUMNS
        || rows > 0 && count != columns) return false;
    int required = (rows + 1) * count;
    if (!ensure(required)) return false;
    int destination = rows * count;
    System.arraycopy(sourceHighs, 0, highs, destination, count);
    System.arraycopy(sourceValues, 0, values, destination, count);
    System.arraycopy(sourceDescriptors, 0, descriptors, destination, count);
    System.arraycopy(sourceNulls, 0, nulls, destination, count);
    System.arraycopy(sourceDefaults, 0, defaults, destination, count);
    columns = count;
    rows++;
    return true;
  }

  boolean append(
      long[] sourceHighs, long[] sourceValues,
      boolean[] sourceNulls, boolean[] sourceDefaults,
      int[] sourceDescriptors, int offset, int count) {
    if (offset < 0 || count <= 0 || offset > sourceValues.length - count
        || offset > sourceHighs.length - count || offset > sourceNulls.length - count
        || offset > sourceDefaults.length - count || offset > sourceDescriptors.length - count
        || count > SqlCommand.MAXIMUM_COLUMNS || rows > 0 && count != columns) return false;
    int required = (rows + 1) * count;
    if (!ensure(required)) return false;
    int destination = rows * count;
    System.arraycopy(sourceHighs, offset, highs, destination, count);
    System.arraycopy(sourceValues, offset, values, destination, count);
    System.arraycopy(sourceDescriptors, offset, descriptors, destination, count);
    System.arraycopy(sourceNulls, offset, nulls, destination, count);
    System.arraycopy(sourceDefaults, offset, defaults, destination, count);
    columns = count;
    rows++;
    return true;
  }

  void reset() {
    int used = rows * columns;
    Arrays.fill(values, 0, used, 0);
    Arrays.fill(highs, 0, used, 0);
    Arrays.fill(descriptors, 0, used, 0);
    Arrays.fill(nulls, 0, used, false);
    Arrays.fill(defaults, 0, used, false);
    rows = 0;
    columns = 0;
  }

  long value(int row, int column) { return values[row * columns + column]; }
  long high(int row, int column) { return highs[row * columns + column]; }
  int typeDescriptor(int row, int column) { return descriptors[row * columns + column]; }
  boolean isNull(int row, int column) { return nulls[row * columns + column]; }
  boolean isDefault(int row, int column) { return defaults[row * columns + column]; }

  void setLiteral(
      int row, int column, long high, long value,
      boolean nullValue, int descriptor) {
    int slot = row * columns + column;
    highs[slot] = nullValue ? 0 : high;
    values[slot] = nullValue ? 0 : value;
    descriptors[slot] = descriptor;
    nulls[slot] = nullValue;
    defaults[slot] = false;
  }

  private boolean ensure(int required) {
    if (required <= values.length) return true;
    int capacity = values.length;
    int maximum = SqlCommand.MAXIMUM_INSERT_ROWS * SqlCommand.MAXIMUM_COLUMNS;
    while (capacity < required) capacity = Math.min(maximum, capacity * 2);
    try {
      long[] nextValues = Arrays.copyOf(values, capacity);
      long[] nextHighs = Arrays.copyOf(highs, capacity);
      int[] nextDescriptors = Arrays.copyOf(descriptors, capacity);
      boolean[] nextNulls = Arrays.copyOf(nulls, capacity);
      boolean[] nextDefaults = Arrays.copyOf(defaults, capacity);
      values = nextValues;
      highs = nextHighs;
      descriptors = nextDescriptors;
      nulls = nextNulls;
      defaults = nextDefaults;
      return true;
    } catch (OutOfMemoryError error) {
      return false;
    }
  }
}
