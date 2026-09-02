package io.riverdb.format.btree;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.format.FormatBytes;
import io.riverdb.format.page.PageCodec;
import java.nio.ByteBuffer;

/** Initialization of one owned variable-key B-tree payload. */
final class TupleBTreePageInitialize {
  private TupleBTreePageInitialize() { }

  static StatusCode initialize(
      ByteBuffer target, int start, int type, int pointer,
      TupleShape shape, long keySchemaId,
      ByteBuffer highKey, int highKeyOffset, int highKeyLength) {
    return initialize(
        target, start, type, 0, pointer, shape, keySchemaId,
        highKey, highKeyOffset, highKeyLength);
  }

  static StatusCode initializeLeaf(
      ByteBuffer target, int start, int leftSibling, int rightSibling,
      TupleShape shape, long keySchemaId,
      ByteBuffer highKey, int highKeyOffset, int highKeyLength) {
    return initialize(
        target, start, TupleBTreePageCodec.TYPE_LEAF, leftSibling, rightSibling,
        shape, keySchemaId, highKey, highKeyOffset, highKeyLength);
  }

  private static StatusCode initialize(
      ByteBuffer target, int start, int type, int leftSibling, int pointer,
      TupleShape shape, long keySchemaId,
      ByteBuffer highKey, int highKeyOffset, int highKeyLength) {
    if (!TupleBTreePageBytes.validPayload(target, start, true)
        || type != TupleBTreePageCodec.TYPE_LEAF && type != TupleBTreePageCodec.TYPE_INTERNAL
        || shape == null || shape.partCount() <= 0
        || shape.partCount() > SqlShapeLimits.MAX_KEY_PARTS || keySchemaId <= 0
        || !TupleBTreePageBytes.validLinks(type, leftSibling, pointer, highKeyLength)
        || !TupleBTreePageBytes.validHighKey(
            highKey, highKeyOffset, highKeyLength, shape)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    TupleBTreePageBytes.clear(target, start);
    int highOffset = 0;
    int freeEnd = PageCodec.MAX_PAYLOAD_BYTES;
    if (highKeyLength > 0) {
      freeEnd -= highKeyLength;
      highOffset = freeEnd;
      TupleBTreePageBytes.copy(
          highKey, highKeyOffset, target, start + highOffset, highKeyLength);
    }
    FormatBytes.putLong(target, start, TupleBTreePageCodec.MAGIC);
    FormatBytes.putInt(target, start + 8, TupleBTreePageCodec.VERSION);
    FormatBytes.putInt(target, start + 12, type);
    FormatBytes.putInt(target, start + 16, 0);
    FormatBytes.putInt(target, start + 20, TupleBTreePageCodec.SLOT_BYTES);
    FormatBytes.putInt(target, start + 24, pointer);
    FormatBytes.putInt(target, start + 28, TupleBTreePageCodec.HEADER_BYTES);
    FormatBytes.putInt(target, start + 32, freeEnd);
    FormatBytes.putInt(target, start + 36, highOffset);
    FormatBytes.putInt(target, start + 40, highKeyLength);
    FormatBytes.putInt(target, start + 44, shape.partCount());
    FormatBytes.putLong(target, start + 48, shape.descriptorHash());
    FormatBytes.putLong(target, start + 56, keySchemaId);
    FormatBytes.putInt(target, start + 64, leftSibling);
    FormatBytes.putInt(target, start + 68, 0);
    return StatusCode.OK;
  }
}
