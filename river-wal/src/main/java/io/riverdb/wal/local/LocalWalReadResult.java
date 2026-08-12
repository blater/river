package io.riverdb.wal.local;

import io.riverdb.format.wal.WalRecordHeader;
import java.nio.ByteBuffer;

/** Caller-owned metadata for one validated WAL read. */
public final class LocalWalReadResult {
  private final WalRecordHeader header = new WalRecordHeader();
  private long nextOffset;
  private ByteBuffer payload;

  public WalRecordHeader header() {
    return header;
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

  public void set(long value, ByteBuffer payloadView) {
    nextOffset = value;
    payload = payloadView;
  }

  public void reset() {
    header.reset();
    nextOffset = 0;
    payload = null;
  }
}
