package io.riverdb.engine.sql;

/** Caller-owned result for one implicit-transaction SQL statement. */
public final class SqlExecutionResult {
  private long commitSequence;
  private long value;
  private int affectedRows;
  private boolean hasValue;

  public void reset() {
    commitSequence = 0;
    value = 0;
    affectedRows = 0;
    hasValue = false;
  }

  void setUpdate(int rows, long committedAt) {
    affectedRows = rows;
    commitSequence = committedAt;
  }

  void setValue(long selectedValue, long committedAt) {
    value = selectedValue;
    hasValue = true;
    affectedRows = 1;
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

  public long commitSequence() {
    return commitSequence;
  }
}
