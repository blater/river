package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;

/** Caller-owned retained statistics with actual-count column storage. */
public final class TableStatistics {
  private final TableStatisticsStorage storage = new TableStatisticsStorage();
  private int tableId;
  private int columnCount;
  private long rowCount;
  private long epoch;

  public void reset() {
    storage.reset(columnCount);
    tableId = 0;
    columnCount = 0;
    rowCount = 0;
    epoch = 0;
  }

  public int tableId() { return tableId; }
  public int columnCount() { return columnCount; }
  public long rowCount() { return rowCount; }
  public long epoch() { return epoch; }
  public long nullCount(int column) { return storage.nullCounts[column]; }
  public long distinctCount(int column) { return storage.distinctCounts[column]; }
  public boolean hasMinMax(int column) { return storage.minMaxColumns.get(column); }
  public long minimumValue(int column) { return storage.minimumValues[column]; }
  public long maximumValue(int column) { return storage.maximumValues[column]; }
  public boolean sampled(int column) { return storage.sampledColumns.get(column); }
  public boolean sampled() { return !storage.sampledColumns.isEmpty(); }

  /** Atomically admits retained capacity and begins a new statistics snapshot. */
  public StatusCode begin(int id, int columns, long analyzedEpoch) {
    StatusCode status = storage.reserve(columns, columnCount);
    reset();
    if (!status.isOk()) return status;
    if (id <= 0 || columns < 0 || analyzedEpoch < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    storage.minMaxColumns.clearForSize(columns);
    storage.sampledColumns.clearForSize(columns);
    tableId = id;
    columnCount = columns;
    epoch = analyzedEpoch;
    return StatusCode.OK;
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
    if (column < 0 || column >= columnCount) return;
    storage.nullCounts[column] = nulls;
    storage.distinctCounts[column] = distinct;
    storage.minimumValues[column] = hasRange ? minimum : 0;
    storage.maximumValues[column] = hasRange ? maximum : 0;
    if (sampled) storage.sampledColumns.set(column);
    if (hasRange) storage.minMaxColumns.set(column);
  }

  public boolean availableFor(TableDefinition table) {
    return table != null && tableId == table.tableId()
        && columnCount == table.columnCount() && epoch >= 0;
  }

  boolean canonicalFor(TableDefinition table) {
    return availableFor(table) && rowCount >= 0 && storage.canonical(columnCount, rowCount);
  }

}
