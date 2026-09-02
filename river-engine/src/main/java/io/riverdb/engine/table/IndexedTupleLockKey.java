package io.riverdb.engine.table;

import io.riverdb.format.btree.TupleKeyCodec;
import java.nio.ByteBuffer;

/** Canonical user-key bytes shared by tuple points and predicate-range endpoints. */
final class IndexedTupleLockKey {
  private IndexedTupleLockKey() { }

  static boolean valid(ByteBuffer key, int offset, int length) {
    return TupleKeyCodec.validate(key, offset, length);
  }

  static int userOffset(ByteBuffer key, int offset, int length) {
    return valid(key, offset, length) ? offset + TupleKeyCodec.headerBytes(key, offset, length) : -1;
  }

  static int userLength(ByteBuffer key, int offset, int length) {
    if (!valid(key, offset, length)) return -1;
    int bytes = length - TupleKeyCodec.headerBytes(key, offset, length);
    return TupleKeyCodec.isPhysical(key, offset, length)
        ? bytes - TupleKeyCodec.LOGICAL_ROW_ID_BYTES : bytes;
  }
}
