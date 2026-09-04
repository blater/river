package io.riverdb.format.btree;

import io.riverdb.base.tuple.TupleShape;
import io.riverdb.format.page.PageCodec;
import java.nio.ByteBuffer;

/** Byte-range and invariant helpers shared by tuple-page codecs. */
final class TupleBTreePageBytes {
  private TupleBTreePageBytes() { }

  static boolean validPayload(ByteBuffer page, int start, boolean writable) {
    return page != null && (!writable || !page.isReadOnly()) && start >= 0
        && page.limit() - start >= PageCodec.MAX_PAYLOAD_BYTES;
  }

  static boolean validRead(
      ByteBuffer source, int start, TupleBTreePageHeader header,
      int index, int expectedType) {
    return validPayload(source, start, false) && header != null
        && header.type() == expectedType && index >= 0 && index < header.entryCount();
  }

  static boolean validValidatedRead(
      ByteBuffer source, int start, TupleBTreePageHeader header,
      int index, int expectedType) {
    return validRead(source, start, header, index, expectedType)
        && header.validates(source, start, expectedType);
  }

  static boolean validLinks(int type, int left, int pointer, int highLength) {
    return type == TupleBTreePageCodec.TYPE_INTERNAL ? left == 0 && pointer > 0
        : left >= 0 && pointer >= 0 && ((pointer == 0) == (highLength == 0));
  }

  static boolean validHighKey(
      ByteBuffer highKey, int offset, int length, TupleShape shape) {
    return length == 0 || length > 0
        && length <= TupleKeyCodec.MAX_PHYSICAL_INDEX_KEY_BYTES
        && highKey != null && offset >= 0 && highKey.limit() - offset >= length
        && TupleKeyCodec.matchesPhysicalIndexKey(highKey, offset, length, shape);
  }

  static void clear(ByteBuffer target, int start) {
    for (int index = 0; index < PageCodec.MAX_PAYLOAD_BYTES; index++) {
      target.put(start + index, (byte) 0);
    }
  }

  static boolean zeroRange(ByteBuffer source, int from, int to) {
    for (int index = from; index < to; index++) {
      if (source.get(index) != 0) return false;
    }
    return true;
  }

  static void copy(
      ByteBuffer source, int sourceOffset, ByteBuffer target, int targetOffset, int bytes) {
    for (int index = 0; index < bytes; index++) {
      target.put(targetOffset + index, source.get(sourceOffset + index));
    }
  }
}
