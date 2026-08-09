package io.riverdb.bench.prototype;

/** Preallocated primitive columns and a reusable selection vector. */
public final class PrimitiveVectorBatch {
  private final long[] accountIds;
  private final long[] balances;
  private final int[] selection;
  private int rowCount;
  private int selectionCount;

  public PrimitiveVectorBatch(int capacity) {
    accountIds = new long[capacity];
    balances = new long[capacity];
    selection = new int[capacity];
  }

  public void setRow(int row, long accountId, long balance) {
    accountIds[row] = accountId;
    balances[row] = balance;
    if (row >= rowCount) {
      rowCount = row + 1;
    }
  }

  public int scanBalanceAtLeast(long minimum) {
    int selected = 0;
    for (int row = 0; row < rowCount; row++) {
      if (balances[row] >= minimum) {
        selection[selected++] = row;
      }
    }
    selectionCount = selected;
    return selected;
  }

  public long sumSelectedAccountIds() {
    long sum = 0L;
    for (int index = 0; index < selectionCount; index++) {
      sum += accountIds[selection[index]];
    }
    return sum;
  }

  public int selectedRow(int index) {
    return selection[index];
  }

  public int rowCount() {
    return rowCount;
  }

  public int selectionCount() {
    return selectionCount;
  }

  public int capacity() {
    return accountIds.length;
  }
}
