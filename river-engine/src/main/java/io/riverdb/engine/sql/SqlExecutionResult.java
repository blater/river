package io.riverdb.engine.sql;

import io.riverdb.engine.relational.TableSchema;

/** Caller-owned result for one implicit-transaction SQL statement. */
public final class SqlExecutionResult {
  private final long[] values = new long[TableSchema.MAXIMUM_COLUMNS];
  private long commitSequence;
  private long value;
  private long key;
  private long nullMask;
  private long varcharMask;
  private int affectedRows;
  private int columnCount;
  private boolean hasValue;
  private boolean transactionActive;

  public void reset() {
    commitSequence = 0;
    value = 0;
    key = 0;
    nullMask = 0;
    varcharMask = 0;
    affectedRows = 0;
    columnCount = 0;
    hasValue = false;
    transactionActive = false;
  }

  void setUpdate(int rows, long committedAt) {
    affectedRows = rows;
    commitSequence = committedAt;
  }

  void setProjection(
      long selectedKey,
      long[] projectedValues,
      long projectedNullMask,
      long projectedVarcharMask,
      int projectedColumnCount,
      long committedAt) {
    key = selectedKey;
    nullMask = projectedNullMask;
    varcharMask = projectedVarcharMask;
    columnCount = projectedColumnCount;
    for (int index = 0; index < projectedColumnCount; index++) {
      values[index] = projectedValues[index];
    }
    value = projectedColumnCount == 0 ? 0 : values[projectedColumnCount - 1];
    hasValue = projectedColumnCount > 0;
    affectedRows = 1;
    commitSequence = committedAt;
  }

  void setCommitSequence(long committedAt) {
    commitSequence = committedAt;
  }

  void setScalar(long scalar, long committedAt) {
    values[0] = scalar;
    value = scalar;
    key = 0;
    nullMask = 0;
    varcharMask = 0;
    affectedRows = 1;
    columnCount = 1;
    hasValue = true;
    commitSequence = committedAt;
  }

  void setTransaction(boolean active, long committedAt) {
    transactionActive = active;
    commitSequence = committedAt;
  }

  public int affectedRows() {
    return affectedRows;
  }

  public boolean hasValue() {
    return hasValue;
  }

  public long value() {
    return value;
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

  public boolean isNull(int index) {
    return index >= 0 && index < columnCount && (nullMask & 1L << index) != 0;
  }

  public long nullMask() {
    return nullMask;
  }

  public boolean isVarchar(int index) {
    return index >= 0
        && index < columnCount
        && (varcharMask & 1L << index) != 0;
  }

  public long varcharMask() {
    return varcharMask;
  }

  public long commitSequence() {
    return commitSequence;
  }

  public boolean transactionActive() {
    return transactionActive;
  }
}
