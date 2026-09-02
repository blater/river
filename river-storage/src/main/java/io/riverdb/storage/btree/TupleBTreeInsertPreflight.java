package io.riverdb.storage.btree;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.btree.TupleKeyCodec;
import java.nio.ByteBuffer;

/** Read-only exact insert admission over a provider-owned tuple tree. */
final class TupleBTreeInsertPreflight {
  private TupleBTreeInsertPreflight() { }

  static StatusCode plan(
      TupleBTree tree, ByteBuffer key, int offset, int length,
      TupleBTreeTreeWorkspace workspace, TupleBTreeInsertPreflightResult result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (!valid(tree, key, offset, length, workspace)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    copyKey(key, offset, length, workspace.keyScratch);
    StatusCode status = TupleBTreeTraversal.physical(
        tree, workspace.keyScratch, 0, length, workspace, true);
    if (!status.isOk()) return status;
    status = tree.provider().pin(workspace.leafPageId, false, workspace.current);
    if (!status.isOk()) return status;
    status = TupleBTreeInsertPreflightLeaf.plan(
        tree, length, workspace, result);
    status = TupleBTreeProviderAccess.release(tree.provider(), workspace.current, status);
    if (!status.isOk()) {
      result.reset();
      return status;
    }
    return result.keyExists() || result.splitLevelCount() == 0
        ? StatusCode.OK
        : TupleBTreeInsertPreflightParents.plan(tree, workspace, result);
  }

  private static boolean valid(
      TupleBTree tree, ByteBuffer key, int offset, int length,
      TupleBTreeTreeWorkspace workspace) {
    return tree != null && tree.isValid(workspace)
        && key != workspace.keyScratch && key != workspace.pageScratch
        && TupleKeyCodec.matchesPhysicalIndexKey(key, offset, length, tree.shape());
  }

  private static void copyKey(ByteBuffer source, int offset, int length, ByteBuffer target) {
    for (int index = 0; index < length; index++) target.put(index, source.get(offset + index));
  }
}
