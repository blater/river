package io.riverdb.storage.btree;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.format.btree.TupleBTreePageCodec;
import io.riverdb.format.btree.TupleKeyCodec;
import java.nio.ByteBuffer;

/** Input and duplicate admission for one internal-page split. */
final class TupleBTreeInternalSplitAdmission {
  private TupleBTreeInternalSplitAdmission() { }

  static StatusCode prepare(
      ByteBuffer source, int sourceStart, ByteBuffer left, int leftStart,
      ByteBuffer right, int rightStart, long schemaId, TupleShape shape,
      ByteBuffer key, int keyOffset, int keyLength, int rightChild,
      TupleBTreeWorkspace workspace, TupleBTreeSplitResult result) {
    if (workspace == null || result == null || source == left || source == right || left == right
        || key == left || key == right || rightChild <= 0
        || !TupleBTreePageSupport.validPayload(left, leftStart, true)
        || !TupleBTreePageSupport.validPayload(right, rightStart, true)
        || !TupleKeyCodec.matchesPhysicalIndexKey(key, keyOffset, keyLength, shape)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    return TupleBTreePageAdmission.validate(
        source, sourceStart, schemaId, shape, TupleBTreePageCodec.TYPE_INTERNAL, workspace);
  }

  static boolean equalAt(
      ByteBuffer page, int start, ByteBuffer key, int keyOffset, int keyLength,
      int index, TupleBTreeWorkspace workspace) {
    if (index >= workspace.header.entryCount()) return false;
    TupleBTreePageSupport.readInternal(page, start, index, workspace);
    return TupleKeyCodec.compare(
        page, start + workspace.internal.keyOffset(), workspace.internal.keyLength(),
        key, keyOffset, keyLength) == 0;
  }
}
