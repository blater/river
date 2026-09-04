package io.riverdb.storage.heap;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.page.PageCodec;
import java.nio.ByteBuffer;

/** Bounded slotted heap layout inside one validated page payload. */
public final class HeapPage {
  public static final int HEADER_BYTES = 32;
  public static final int SLOT_BYTES = 8;
  /** Largest row representable by one slot in the fixed page payload. */
  public static final int MAXIMUM_ROW_BYTES =
      PageCodec.MAX_PAYLOAD_BYTES - HEADER_BYTES - SLOT_BYTES;
  public static final int VERSION = 1;

  private static final long MAGIC = 0x5249564552484550L; // RIVERHEP

  private HeapPage() {
  }

  public static StatusCode initialize(ByteBuffer page) {
    if (page == null || page.limit() < HEADER_BYTES + SLOT_BYTES + 1) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int index = 0; index < page.limit(); index++) {
      page.put(index, (byte) 0);
    }
    putLong(page, 0, MAGIC);
    putInt(page, 8, VERSION);
    putInt(page, 12, 0);
    putInt(page, 16, HEADER_BYTES);
    putInt(page, 20, page.limit());
    putLong(page, 24, 0);
    return StatusCode.OK;
  }

  public static StatusCode validate(ByteBuffer page) {
    if (page == null || page.limit() < HEADER_BYTES) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int rowCount = getInt(page, 12);
    int freeStart = getInt(page, 16);
    int freeEnd = getInt(page, 20);
    if (getLong(page, 0) != MAGIC
        || getInt(page, 8) != VERSION
        || rowCount < 0
        || rowCount > (page.limit() - HEADER_BYTES) / SLOT_BYTES
        || freeStart != HEADER_BYTES + rowCount * SLOT_BYTES
        || freeStart < HEADER_BYTES
        || freeStart > freeEnd
        || freeEnd > page.limit()
        || getLong(page, 24) != 0) {
      return StatusCode.CORRUPTION;
    }
    int previousOffset = page.limit();
    for (int slot = 0; slot < rowCount; slot++) {
      int slotOffset = HEADER_BYTES + slot * SLOT_BYTES;
      int rowOffset = getInt(page, slotOffset);
      int rowLength = getInt(page, slotOffset + 4);
      if (rowLength <= 0
          || rowOffset < freeEnd
          || rowOffset > page.limit()
          || rowLength > page.limit() - rowOffset
          || rowOffset + rowLength != previousOffset
          || rowOffset + rowLength > page.limit()) {
        return StatusCode.CORRUPTION;
      }
      previousOffset = rowOffset;
    }
    return previousOffset == freeEnd ? StatusCode.OK : StatusCode.CORRUPTION;
  }

  public static StatusCode insert(
      ByteBuffer page,
      ByteBuffer row,
      HeapInsertResult result) {
    if (page == null || row == null || result == null || !row.hasRemaining()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int rowBytes = row.remaining();
    return insertFrom(page, row, row.position(), rowBytes, result);
  }

  /** Inserts bytes from an absolute source range without creating a buffer view. */
  public static StatusCode insertFrom(
      ByteBuffer page,
      ByteBuffer source,
      int sourceOffset,
      int rowBytes,
      HeapInsertResult result) {
    if (page == null
        || source == null
        || result == null
        || sourceOffset < 0
        || rowBytes <= 0
        || source.limit() - sourceOffset < rowBytes) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    int rowCount = getInt(page, 12);
    int freeStart = getInt(page, 16);
    int freeEnd = getInt(page, 20);
    int newFreeStart = freeStart + SLOT_BYTES;
    int newFreeEnd = freeEnd - rowBytes;
    if (newFreeStart > newFreeEnd) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    for (int index = 0; index < rowBytes; index++) {
      page.put(newFreeEnd + index, source.get(sourceOffset + index));
    }
    putInt(page, freeStart, newFreeEnd);
    putInt(page, freeStart + 4, rowBytes);
    putInt(page, 12, rowCount + 1);
    putInt(page, 16, newFreeStart);
    putInt(page, 20, newFreeEnd);
    result.setRowId(rowCount + 1);
    return StatusCode.OK;
  }

  public static boolean canInsert(ByteBuffer page, int rowBytes) {
    if (page == null || rowBytes <= 0) {
      return false;
    }
    return getInt(page, 16) + SLOT_BYTES <= getInt(page, 20) - rowBytes;
  }

  public static int availableBytes(ByteBuffer page) {
    if (page == null) {
      return 0;
    }
    return getInt(page, 20) - getInt(page, 16);
  }

  public static StatusCode fetch(
      ByteBuffer page,
      int rowId,
      HeapRowResult result) {
    if (page == null || result == null || rowId <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    int rowCount = getInt(page, 12);
    if (rowId > rowCount) {
      return StatusCode.CONFLICT;
    }
    int slotOffset = HEADER_BYTES + (rowId - 1) * SLOT_BYTES;
    result.set(page, rowId, getInt(page, slotOffset), getInt(page, slotOffset + 4));
    return StatusCode.OK;
  }

  /** Copies one row into an absolute destination range without creating a buffer view. */
  public static StatusCode copyRowTo(
      ByteBuffer page,
      int rowId,
      ByteBuffer destination,
      int destinationOffset) {
    if (page == null || destination == null || rowId <= 0 || destinationOffset < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int rowCount = getInt(page, 12);
    if (rowId > rowCount) {
      return StatusCode.CONFLICT;
    }
    int slotOffset = HEADER_BYTES + (rowId - 1) * SLOT_BYTES;
    int rowOffset = getInt(page, slotOffset);
    int rowLength = getInt(page, slotOffset + 4);
    if (destination.limit() - destinationOffset < rowLength) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    for (int index = 0; index < rowLength; index++) {
      destination.put(destinationOffset + index, page.get(rowOffset + index));
    }
    return StatusCode.OK;
  }

  public static int rowLength(ByteBuffer page, int rowId) {
    if (page == null || rowId <= 0 || rowId > getInt(page, 12)) {
      return 0;
    }
    return getInt(page, HEADER_BYTES + (rowId - 1) * SLOT_BYTES + 4);
  }

  public static StatusCode next(
      ByteBuffer page,
      HeapScanCursor cursor,
      HeapRowResult result) {
    if (page == null || cursor == null || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (cursor.nextSlot() >= getInt(page, 12)) {
      result.reset();
      return StatusCode.CONFLICT;
    }
    int rowId = cursor.nextSlot() + 1;
    int slotOffset = HEADER_BYTES + cursor.nextSlot() * SLOT_BYTES;
    result.set(page, rowId, getInt(page, slotOffset), getInt(page, slotOffset + 4));
    cursor.advance();
    return StatusCode.OK;
  }

  public static int rowCount(ByteBuffer page) {
    return getInt(page, 12);
  }

  /** Identifies this page type before invoking its full structural validator. */
  public static boolean isHeap(ByteBuffer page) {
    return page != null
        && page.limit() >= HEADER_BYTES
        && getLong(page, 0) == MAGIC
        && getInt(page, 8) == VERSION;
  }

  private static void putInt(ByteBuffer target, int offset, int value) {
    target.put(offset, (byte) value);
    target.put(offset + 1, (byte) (value >>> 8));
    target.put(offset + 2, (byte) (value >>> 16));
    target.put(offset + 3, (byte) (value >>> 24));
  }

  private static int getInt(ByteBuffer source, int offset) {
    return Byte.toUnsignedInt(source.get(offset))
        | Byte.toUnsignedInt(source.get(offset + 1)) << 8
        | Byte.toUnsignedInt(source.get(offset + 2)) << 16
        | Byte.toUnsignedInt(source.get(offset + 3)) << 24;
  }

  private static void putLong(ByteBuffer target, int offset, long value) {
    putInt(target, offset, (int) value);
    putInt(target, offset + 4, (int) (value >>> 32));
  }

  private static long getLong(ByteBuffer source, int offset) {
    return Integer.toUnsignedLong(getInt(source, offset))
        | Integer.toUnsignedLong(getInt(source, offset + 4)) << 32;
  }
}
