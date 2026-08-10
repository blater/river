package io.riverdb.engine.sql;

/** Caller-owned result for one implicit-transaction SQL statement. */
public final class SqlExecutionResult {
  private long commitSequence;
  private long value;
  private long key;
  private int affectedRows;
  private boolean hasValue;
  private boolean transactionActive;

  public void reset() {
    commitSequence = 0;
    value = 0;
    key = 0;
    affectedRows = 0;
    hasValue = false;
    transactionActive = false;
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

  void setRow(long selectedKey, long selectedValue, long committedAt) {
    key = selectedKey;
    value = selectedValue;
    hasValue = true;
    affectedRows = 1;
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

  public long commitSequence() {
    return commitSequence;
  }

  public boolean transactionActive() {
    return transactionActive;
  }
}
