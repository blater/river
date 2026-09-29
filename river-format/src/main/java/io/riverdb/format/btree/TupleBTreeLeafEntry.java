package io.riverdb.format.btree;

/** Caller-owned leaf slot whose key, inline value and overflow pointer borrow one page. */
public final class TupleBTreeLeafEntry {
  private int keyOffset;
  private int keyLength;
  private int valueOffset;
  private int valueLength;
  private int overflowPageId;
  private long overflowGeneration;
  private long modificationSequence;
  private long logicalRowId;

  void set(
      int offset, int length, int rowValueOffset, int rowValueLength,
      int overflowId, long overflowVersion, long modifiedAt, long rowId) {
    keyOffset = offset;
    keyLength = length;
    valueOffset = rowValueOffset;
    valueLength = rowValueLength;
    overflowPageId = overflowId;
    overflowGeneration = overflowVersion;
    modificationSequence = modifiedAt;
    logicalRowId = rowId;
  }

  public void reset() { set(0, 0, 0, 0, 0, 0, 0, 0); }
  public int keyOffset() { return keyOffset; }
  public int keyLength() { return keyLength; }
  public int valueOffset() { return valueOffset; }
  public int valueLength() { return valueLength; }
  public int overflowPageId() { return overflowPageId; }
  public long overflowGeneration() { return overflowGeneration; }
  public long modificationSequence() { return modificationSequence; }
  public long logicalRowId() { return logicalRowId; }
}
