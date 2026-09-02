package io.riverdb.storage.heap;

import io.riverdb.base.error.StatusCode;
import java.nio.ByteBuffer;

/** Reusable heap-row view that can retain bytes across provider pin lifetimes. */
public final class HeapRowResult {
  private ByteBuffer page;
  private ByteBuffer ownedPage;
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

  /** Reads one validated internal row byte without creating a buffer view. */
  public byte getByte(int relativeOffset) {
    return page.get(offset + relativeOffset);
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

  /** Copies the current borrowed row into geometrically grown result-owned storage. */
  public StatusCode retainBytes() {
    if (page == null || length <= 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    ByteBuffer source = page;
    int sourceOffset = offset;
    if (ownedPage == null || ownedPage.capacity() < length) {
      try {
        ownedPage = ByteBuffer.allocate(retainedCapacity(length));
      } catch (OutOfMemoryError exhausted) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
    }
    for (int index = 0; index < length; index++) {
      ownedPage.put(index, source.get(sourceOffset + index));
    }
    page = ownedPage;
    offset = 0;
    return StatusCode.OK;
  }

  private static int retainedCapacity(int required) {
    int capacity = 64;
    while (capacity < required && capacity <= Integer.MAX_VALUE / 2) capacity <<= 1;
    return capacity < required ? required : capacity;
  }

  public void copyFrom(HeapRowResult source) {
    page = source.page;
    rowId = source.rowId;
    offset = source.offset;
    length = source.length;
  }
}
