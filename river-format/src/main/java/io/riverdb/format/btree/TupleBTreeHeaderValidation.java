package io.riverdb.format.btree;

import io.riverdb.base.tuple.TupleShape;
import io.riverdb.format.FormatBytes;
import io.riverdb.format.page.PageCodec;
import java.nio.ByteBuffer;

/** Header invariants for a variable-key B-tree payload. */
final class TupleBTreeHeaderValidation {
  private TupleBTreeHeaderValidation() { }

  static boolean valid(
      ByteBuffer source, int start, int type, int count, int pointer, int leftSibling,
      int freeStart, int freeEnd, int highOffset, int highLength,
      int arity, long descriptorHash, long schemaId,
      long expectedSchemaId, TupleShape expectedShape) {
    return FormatBytes.getLong(source, start) == TupleBTreePageCodec.MAGIC
        && FormatBytes.getInt(source, start + 8) == TupleBTreePageCodec.VERSION
        && (type == TupleBTreePageCodec.TYPE_LEAF || type == TupleBTreePageCodec.TYPE_INTERNAL)
        && count >= 0 && count <= TupleBTreePageCodec.MAXIMUM_SLOTS
        && FormatBytes.getInt(source, start + 20) == TupleBTreePageCodec.SLOT_BYTES
        && TupleBTreePageBytes.validLinks(type, leftSibling, pointer, highLength)
        && freeStart == TupleBTreePageCodec.HEADER_BYTES
            + count * TupleBTreePageCodec.SLOT_BYTES
        && freeStart <= freeEnd && freeEnd <= PageCodec.MAX_PAYLOAD_BYTES
        && highLength >= 0 && highLength <= TupleKeyCodec.MAX_PHYSICAL_INDEX_KEY_BYTES
        && (highLength != 0 || highOffset == 0)
        && arity == expectedShape.partCount()
        && descriptorHash == expectedShape.descriptorHash()
        && schemaId == expectedSchemaId && schemaId > 0
        && FormatBytes.getInt(source, start + 68) == 0;
  }
}
