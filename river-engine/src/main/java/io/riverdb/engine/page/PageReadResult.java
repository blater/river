package io.riverdb.engine.page;

import java.nio.ByteBuffer;

/** Provider-owned page payload view, valid until the next store operation. */
public final class PageReadResult {
  private ByteBuffer payload;
  private long walRecordEnd;

  public ByteBuffer payload() {
    return payload;
  }

  public long walRecordEnd() {
    return walRecordEnd;
  }

  public void set(ByteBuffer payloadView, long recordEnd) {
    payload = payloadView;
    walRecordEnd = recordEnd;
  }

  public void reset() {
    payload = null;
    walRecordEnd = 0;
  }
}
