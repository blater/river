package io.riverdb.storage.heap;

import io.riverdb.base.error.StatusCode;
import java.nio.ByteBuffer;

/** Borrowed heap-row view without creating a per-row ByteBuffer slice. */
public final class HeapRowResult {
  private ByteBuffer page;
  private int rowId;
  private int offset;
  private int length;

  public int rowId() {
    return rowId;
  }

  public int length() {
    return length;
  }

  /** Reads a validated internal BIGINT field directly from the borrowed row. */
  public long getLong(int relativeOffset) {
    return page.getLong(offset + relativeOffset);
  }

  public StatusCode copyTo(ByteBuffer destination) {
    if (destination == null || destination.remaining() < length) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int destinationStart = destination.position();
    for (int index = 0; index < length; index++) {
      destination.put(destinationStart + index, page.get(offset + index));
    }
    destination.position(destinationStart + length);
    return StatusCode.OK;
  }

  public void set(ByteBuffer source, int id, int rowOffset, int rowLength) {
    page = source;
    rowId = id;
    offset = rowOffset;
    length = rowLength;
  }

  public void reset() {
    page = null;
    rowId = 0;
    offset = 0;
    length = 0;
  }

  public void copyFrom(HeapRowResult source) {
    page = source.page;
    rowId = source.rowId;
    offset = source.offset;
    length = source.length;
  }
}
