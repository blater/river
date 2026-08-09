package io.riverdb.wal.local;

import io.riverdb.format.wal.WalRecordHeader;

/** Caller-owned metadata for one validated WAL read. */
public final class LocalWalReadResult {
  private final WalRecordHeader header = new WalRecordHeader();
  private long nextOffset;

  public WalRecordHeader header() {
    return header;
  }

  public long nextOffset() {
    return nextOffset;
  }

  public void setNextOffset(long value) {
    nextOffset = value;
  }

  public void reset() {
    header.reset();
    nextOffset = 0;
  }
}
