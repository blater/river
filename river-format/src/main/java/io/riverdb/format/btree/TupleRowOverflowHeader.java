package io.riverdb.format.btree;

/** Caller-owned structural view of one validated immutable overflow value. */
public final class TupleRowOverflowHeader {
  private long logicalRowId;
  private int valueLength;
  private long retiredAtCommitSequence;

  void set(long rowId, int length, long retiredAt) {
    logicalRowId = rowId;
    valueLength = length;
    retiredAtCommitSequence = retiredAt;
  }

  public void reset() { set(0, 0, 0); }
  public long logicalRowId() { return logicalRowId; }
  public int valueLength() { return valueLength; }
  public long retiredAtCommitSequence() { return retiredAtCommitSequence; }
}
