package io.riverdb.storage.btree;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.btree.TupleKeyCodec;
import java.nio.ByteBuffer;

/** Validates and stabilizes one physical key before tree traversal or mutation. */
final class TupleBTreeKeyInput {
  private TupleBTreeKeyInput() { }

  static StatusCode copy(
      TupleBTree tree, ByteBuffer source, int offset, int length,
      TupleBTreeTreeWorkspace workspace) {
    if (tree == null || !tree.isValid(workspace)
        || source == workspace.keyScratch || source == workspace.pageScratch
        || !TupleKeyCodec.matchesPhysicalIndexKey(
            source, offset, length, tree.shape())) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int index = 0; index < length; index++) {
      workspace.keyScratch.put(index, source.get(offset + index));
    }
    return StatusCode.OK;
  }
}
