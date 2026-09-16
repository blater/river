package io.riverdb.storage.btree;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.format.btree.TupleBTreePageCodec;
import io.riverdb.format.btree.TupleKeyCodec;
import java.nio.ByteBuffer;

/** Input and duplicate admission for one leaf split. */
final class TupleBTreeLeafSplitAdmission {
  private TupleBTreeLeafSplitAdmission() { }

  static StatusCode prepare(
      ByteBuffer source, int sourceStart, ByteBuffer left, int leftStart,
      ByteBuffer right, int rightStart, int leftPageId, int rightPageId,
      long schemaId, TupleShape shape, ByteBuffer key, int keyOffset, int keyLength,
      TupleBTreeWorkspace workspace, TupleBTreeSplitResult result) {
    if (!validInputs(
        source, sourceStart, left, leftStart, right, rightStart,
        leftPageId, rightPageId, key, keyOffset, keyLength, workspace, result)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode status = TupleBTreePageAdmission.validate(
        source, sourceStart, schemaId, shape, TupleBTreePageCodec.TYPE_LEAF, workspace);
    if (!status.isOk()) return status;
    return TupleKeyCodec.matchesPhysicalIndexKey(key, keyOffset, keyLength, shape)
        ? StatusCode.OK : StatusCode.INVALID_EXTERNAL_INPUT;
  }

  private static boolean validInputs(
      ByteBuffer source, int sourceStart, ByteBuffer left, int leftStart,
      ByteBuffer right, int rightStart, int leftPageId, int rightPageId,
      ByteBuffer key, int keyOffset, int keyLength,
      TupleBTreeWorkspace workspace, TupleBTreeSplitResult result) {
    if (workspace == null || result == null) return false;
    if (source == left || source == right || left == right) return false;
    if (key == left || key == right) return false;
    if (leftPageId <= 0 || rightPageId <= 0 || leftPageId == rightPageId) return false;
    if (!TupleBTreePageSupport.validPayload(left, leftStart, true)
        || !TupleBTreePageSupport.validPayload(right, rightStart, true)) return false;
    return true;
  }

  static int equalAt(
      ByteBuffer page, int start, ByteBuffer key, int keyOffset, int keyLength,
      int index, TupleBTreeWorkspace workspace) {
    if (index >= workspace.header.entryCount()) return 0;
    if (!TupleBTreePageSupport.readLeaf(page, start, index, workspace)) return -1;
    return TupleKeyCodec.compare(
        page, start + workspace.leaf.keyOffset(), workspace.leaf.keyLength(),
        key, keyOffset, keyLength) == 0 ? 1 : 0;
  }
}
