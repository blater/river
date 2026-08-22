package io.riverdb.engine.relational;

/** Caller-owned bounded statistics for one analyzed physical table. */
public final class TableStatistics {
  private final long[] nullCounts = new long[TableSchema.MAXIMUM_COLUMNS];
  private final long[] distinctCounts = new long[TableSchema.MAXIMUM_COLUMNS];
  private final long[] minimumValues = new long[TableSchema.MAXIMUM_COLUMNS];
  private final long[] maximumValues = new long[TableSchema.MAXIMUM_COLUMNS];
  private int tableId;
  private int columnCount;
  private long rowCount;
  private long epoch;
  private long minMaxMask;
  private long sampledMask;

  public void reset() {
    for (int column = 0; column < columnCount; column++) {
      nullCounts[column] = 0;
      distinctCounts[column] = 0;
      minimumValues[column] = 0;
      maximumValues[column] = 0;
    }
    tableId = 0;
    columnCount = 0;
    rowCount = 0;
    epoch = 0;
    minMaxMask = 0;
    sampledMask = 0;
  }

  public int tableId() { return tableId; }
  public int columnCount() { return columnCount; }
  public long rowCount() { return rowCount; }
  public long epoch() { return epoch; }
  public long nullCount(int column) { return nullCounts[column]; }
  public long distinctCount(int column) { return distinctCounts[column]; }
  public boolean hasMinMax(int column) { return (minMaxMask & 1L << column) != 0; }
  public long minimumValue(int column) { return minimumValues[column]; }
  public long maximumValue(int column) { return maximumValues[column]; }
  public boolean sampled(int column) { return (sampledMask & 1L << column) != 0; }
  public boolean sampled() { return sampledMask != 0; }

  public void begin(int id, int columns, long analyzedEpoch) {
    reset();
    tableId = id;
    columnCount = columns;
    epoch = analyzedEpoch;
  }

  public void setRowCount(long rows) { rowCount = rows; }

  public void setColumn(
      int column,
      long nulls,
      long distinct,
      boolean sampled,
      boolean hasRange,
      long minimum,
      long maximum) {
    nullCounts[column] = nulls;
    distinctCounts[column] = distinct;
    minimumValues[column] = hasRange ? minimum : 0;
    maximumValues[column] = hasRange ? maximum : 0;
    if (sampled) sampledMask |= 1L << column;
    if (hasRange) minMaxMask |= 1L << column;
  }

  public boolean availableFor(TableDefinition table) {
    return table != null
        && tableId == table.tableId()
        && columnCount == table.columnCount()
        && epoch >= 0;
  }

  boolean canonicalFor(TableDefinition table) {
    if (!availableFor(table) || rowCount < 0) return false;
    long columnsMask = (1L << columnCount) - 1;
    if ((minMaxMask & ~columnsMask) != 0 || (sampledMask & ~columnsMask) != 0) {
      return false;
    }
    for (int column = 0; column < columnCount; column++) {
      long nulls = nullCounts[column];
      long distinct = distinctCounts[column];
      long nonNull = rowCount - nulls;
      if (nulls < 0 || nulls > rowCount
          || distinct < 0 || distinct > nonNull
          || (distinct == 0) != (nonNull == 0)
          || nonNull == 0 && hasMinMax(column)) return false;
    }
    return true;
  }

  long minMaxMask() { return minMaxMask; }
  long sampledMask() { return sampledMask; }
}
