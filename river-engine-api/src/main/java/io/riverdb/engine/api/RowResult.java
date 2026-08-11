package io.riverdb.engine.api;

import io.riverdb.base.error.StatusCode;

/** Reusable bounded row result; unavailable with OK denotes end of stream. */
public final class RowResult {
  private final long[] values = new long[CommandResult.MAXIMUM_COLUMNS];
  private long key;
  private int columnCount;
  private boolean available;

  public void reset() {
    key = 0;
    columnCount = 0;
    available = false;
  }

  public StatusCode complete(
      long rowKey,
      long[] sourceValues,
      int columns) {
    if (sourceValues == null || columns <= 0 || columns > values.length) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    reset();
    key = rowKey;
    columnCount = columns;
    available = true;
    for (int index = 0; index < columns; index++) {
      values[index] = sourceValues[index];
    }
    return StatusCode.OK;
  }

  public long key() {
    return key;
  }

  public int columnCount() {
    return columnCount;
  }

  public long valueAt(int index) {
    return index >= 0 && index < columnCount ? values[index] : 0;
  }

  public boolean isAvailable() {
    return available;
  }
}
