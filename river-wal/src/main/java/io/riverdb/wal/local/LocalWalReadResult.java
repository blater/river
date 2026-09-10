package io.riverdb.wal.local;

import io.riverdb.format.wal.WalRecordHeader;
import java.nio.ByteBuffer;

/** Caller-owned metadata for one validated WAL read. */
public final class LocalWalReadResult {
  private final WalRecordHeader header = new WalRecordHeader();
  private long recordEnd;
  private long nextOffset;
  private ByteBuffer payload;

  public WalRecordHeader header() {
    return header;
  }

  /** Physical end of the validated record bytes, before any group footer. */
  public long recordEnd() {
    return recordEnd;
  }

  public long nextOffset() {
    return nextOffset;
  }

  /**
   * Provider-owned view, valid until the next ordinary read or until the forced batch is released.
   */
  public ByteBuffer payload() {
    return payload;
  }

  public void set(long recordEndValue, long nextValue, ByteBuffer payloadView) {
    recordEnd = recordEndValue;
    nextOffset = nextValue;
    payload = payloadView;
  }

  public void reset() {
    header.reset();
    recordEnd = 0;
    nextOffset = 0;
    payload = null;
  }
}
