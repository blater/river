package io.riverdb.format.btree;

/** Caller-owned structural view of one validated immutable overflow value. */
public final class TupleRowOverflowHeader {
  private long logicalRowId;
  private int valueLength;

  void set(long rowId, int length) {
    logicalRowId = rowId;
    valueLength = length;
  }

  public void reset() { set(0, 0); }
  public long logicalRowId() { return logicalRowId; }
  public int valueLength() { return valueLength; }
}
