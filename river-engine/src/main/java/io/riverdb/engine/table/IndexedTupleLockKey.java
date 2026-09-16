package io.riverdb.engine.table;

import io.riverdb.format.btree.TupleKeyCodec;
import java.nio.ByteBuffer;

/** Canonical user-key bytes shared by tuple points and predicate-range endpoints. */
final class IndexedTupleLockKey {
  private IndexedTupleLockKey() { }

  static boolean valid(ByteBuffer key, int offset, int length) {
    return TupleKeyCodec.validate(key, offset, length);
  }

  // Projections require an admitted key, stable for the synchronous operation.
  static int userOffset(ByteBuffer key, int offset, int length) {
    return offset + TupleKeyCodec.headerBytes(key, offset, length);
  }

  static int userLength(ByteBuffer key, int offset, int length) {
    int bytes = length - TupleKeyCodec.headerBytes(key, offset, length);
    return TupleKeyCodec.isPhysical(key, offset, length)
        ? bytes - TupleKeyCodec.LOGICAL_ROW_ID_BYTES : bytes;
  }
}
