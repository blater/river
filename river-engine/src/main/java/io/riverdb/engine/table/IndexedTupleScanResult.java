package io.riverdb.engine.table;

import java.nio.ByteBuffer;

/** Caller-owned tuple entry; committed value bytes borrow the cursor's pinned leaf. */
public final class IndexedTupleScanResult {
  private long logicalRowId;
  private ByteBuffer page;
  private int valueOffset;
  private int valueLength;
  private int overflowPageId;
  private long overflowGeneration;
  private long modificationSequence;

  public void reset() {
    logicalRowId = 0;
    page = null;
    valueOffset = 0;
    valueLength = 0;
    overflowPageId = 0;
    overflowGeneration = 0;
    modificationSequence = 0;
  }
  public long logicalRowId() { return logicalRowId; }
  public boolean committed() { return page != null; }
  public ByteBuffer page() { return page; }
  public int valueOffset() { return valueOffset; }
  public int valueLength() { return valueLength; }
  public int overflowPageId() { return overflowPageId; }
  public long overflowGeneration() { return overflowGeneration; }
  public long modificationSequence() { return modificationSequence; }

  void setPending(long rowId) { logicalRowId = rowId; }

  void setCommitted(
      long rowId, ByteBuffer pinnedPage, int rowOffset, int rowLength,
      int overflowId, long overflowVersion, long modifiedAt) {
    logicalRowId = rowId;
    page = pinnedPage;
    valueOffset = rowOffset;
    valueLength = rowLength;
    overflowPageId = overflowId;
    overflowGeneration = overflowVersion;
    modificationSequence = modifiedAt;
  }
}
