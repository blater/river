package io.riverdb.storage.heap;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.BoundedByteSource;
import io.riverdb.format.FormatBytes;
import io.riverdb.format.row.StoredTableRowHeaderCodec;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** Reusable heap-row view that can retain bytes across provider pin lifetimes. */
public final class HeapRowResult implements BoundedByteSource {
  private ByteBuffer page;
  private ByteBuffer ownedPage;
  private ByteBuffer retainedReadOnly;
  private HeapRowProjection projection;
  private int rowId;
  private int offset;
  private int length;

  public int rowId() {
    return rowId;
  }

  public int length() {
    return length;
  }

  /** Applies only to the next retained row; the caller owns this plan. */
  public void retentionProjection(HeapRowProjection plan) { projection = plan; }

  /** Borrowed until this result is reused; only rows retained by this result expose it. */
  public ByteBuffer retainedReadOnlyBytes() {
    if (page != ownedPage || retainedReadOnly == null) return null;
    retainedReadOnly.position(0).limit(length);
    return retainedReadOnly;
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
    if (page == ownedPage) return StatusCode.OK;
    if (projection != null) return retainProjected();
    ByteBuffer source = page;
    int sourceOffset = offset;
    if (ownedPage == null || ownedPage.capacity() < length) {
      ByteBuffer grownPage;
      ByteBuffer grownReadOnly;
      try {
        grownPage = ByteBuffer.allocate(retainedCapacity(length));
        grownReadOnly = grownPage.asReadOnlyBuffer();
      } catch (OutOfMemoryError exhausted) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      ownedPage = grownPage;
      retainedReadOnly = grownReadOnly;
    }
    for (int index = 0; index < length; index++) {
      ownedPage.put(index, source.get(sourceOffset + index));
    }
    page = ownedPage;
    offset = 0;
    return StatusCode.OK;
  }

  private StatusCode retainProjected() {
    int fixed = projection.fixedBytes();
    if (length < fixed || fixed < projection.metadataBytes()) return StatusCode.CORRUPTION;
    ByteBuffer source = page;
    int sourceStart = offset;
    int required = fixed;
    for (int index = 0; index < projection.count(); index++) {
      if (!projection.variable(index) || nullAt(source, sourceStart, projection.column(index))) {
        continue;
      }
      int slot = sourceStart + projection.offset(index);
      int textOffset = FormatBytes.getInt(source, slot);
      int textBytes = FormatBytes.getInt(source, slot + Integer.BYTES);
      if (textOffset < fixed || textBytes < 0 || textOffset > length - textBytes
          || required > HeapPage.MAXIMUM_ROW_BYTES - textBytes) {
        return StatusCode.CORRUPTION;
      }
      required += textBytes;
    }
    if (ownedPage == null || ownedPage.capacity() < required) {
      ByteBuffer grownPage;
      ByteBuffer grownReadOnly;
      try {
        grownPage = ByteBuffer.allocate(retainedCapacity(required));
        grownReadOnly = grownPage.asReadOnlyBuffer();
      } catch (OutOfMemoryError exhausted) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      ownedPage = grownPage;
      retainedReadOnly = grownReadOnly;
    }
    Arrays.fill(ownedPage.array(), 0, fixed, (byte) 0);
    for (int index = 0; index < projection.metadataBytes(); index++) {
      ownedPage.put(index, source.get(sourceStart + index));
    }
    int nextText = fixed;
    for (int index = 0; index < projection.count(); index++) {
      if (nullAt(source, sourceStart, projection.column(index))) continue;
      int slot = projection.offset(index);
      if (projection.variable(index)) {
        int textOffset = FormatBytes.getInt(source, sourceStart + slot);
        int textBytes = FormatBytes.getInt(source, sourceStart + slot + Integer.BYTES);
        FormatBytes.putInt(ownedPage, slot, nextText);
        FormatBytes.putInt(ownedPage, slot + Integer.BYTES, textBytes);
        for (int byteIndex = 0; byteIndex < textBytes; byteIndex++) {
          ownedPage.put(nextText + byteIndex,
              source.get(sourceStart + textOffset + byteIndex));
        }
        nextText += textBytes;
      } else {
        for (int byteIndex = 0; byteIndex < projection.width(index); byteIndex++) {
          ownedPage.put(slot + byteIndex, source.get(sourceStart + slot + byteIndex));
        }
      }
    }
    page = ownedPage;
    offset = 0;
    length = required;
    return StatusCode.OK;
  }

  private static boolean nullAt(ByteBuffer source, int start, int column) {
    int flag = source.get(start + StoredTableRowHeaderCodec.HEADER_BYTES
        + (column >>> 3)) & 0xff;
    return (flag & (1 << (column & 7))) != 0;
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
