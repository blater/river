package io.riverdb.engine.api;

import io.riverdb.base.error.StatusCode;

/** Reusable bounded result for one command or query close. */
public final class CommandResult {
  public static final int MAXIMUM_COLUMNS = 8;

  private final long[] values = new long[MAXIMUM_COLUMNS];
  private long commitSequence;
  private long key;
  private int affectedRows;
  private int columnCount;
  private boolean rowAvailable;
  private boolean transactionActive;

  public void reset() {
    commitSequence = 0;
    key = 0;
    affectedRows = 0;
    columnCount = 0;
    rowAvailable = false;
    transactionActive = false;
  }

  public StatusCode complete(
      int rows,
      long committedAt,
      boolean activeTransaction,
      boolean hasRow,
      long selectedKey,
      long[] sourceValues,
      int columns) {
    if (rows < 0
        || committedAt < 0
        || columns < 0
        || columns > values.length
        || hasRow != (columns > 0)
        || columns > 0 && sourceValues == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    reset();
    affectedRows = rows;
    commitSequence = committedAt;
    transactionActive = activeTransaction;
    rowAvailable = hasRow;
    key = selectedKey;
    columnCount = columns;
    for (int index = 0; index < columns; index++) {
      values[index] = sourceValues[index];
    }
    return StatusCode.OK;
  }

  public int affectedRows() {
    return affectedRows;
  }

  public long commitSequence() {
    return commitSequence;
  }

  public boolean transactionActive() {
    return transactionActive;
  }

  public boolean rowAvailable() {
    return rowAvailable;
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
}
