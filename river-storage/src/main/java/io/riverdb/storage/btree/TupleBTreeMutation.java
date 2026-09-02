package io.riverdb.storage.btree;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.btree.TupleKeyCodec;
import java.nio.ByteBuffer;

/** Whole-tree mutation admission and leaf dispatch; any non-OK result requires transaction abort. */
final class TupleBTreeMutation {
  private TupleBTreeMutation() { }

  static StatusCode insert(
      TupleBTree tree, ByteBuffer key, int offset, int length,
      TupleBTreeTreeWorkspace workspace) {
    StatusCode status = prepare(tree, key, offset, length, workspace);
    if (!status.isOk()) return status;
    int originalRoot = tree.provider().rootPageId();
    status = TupleBTreeTraversal.physical(tree, key, offset, length, workspace, true);
    if (!status.isOk()) return status;
    status = tree.provider().pin(workspace.leafPageId, true, workspace.current);
    if (!status.isOk()) return status;
    status = TupleBTreeLeafPage.insert(
        workspace.current.page(), workspace.current.start(), workspace.pageScratch, 0,
        tree.schemaId(), tree.shape(), key, offset, length, workspace.page);
    if (status != StatusCode.RESOURCE_EXHAUSTED) {
      return TupleBTreeProviderAccess.release(tree.provider(), workspace.current, status);
    }
    return TupleBTreeSplitPropagation.leaf(
        tree, key, offset, length, originalRoot, workspace);
  }

  static StatusCode delete(
      TupleBTree tree, ByteBuffer key, int offset, int length,
      TupleBTreeTreeWorkspace workspace) {
    StatusCode status = prepare(tree, key, offset, length, workspace);
    if (!status.isOk()) return status;
    status = TupleBTreeTraversal.physical(tree, key, offset, length, workspace, false);
    if (!status.isOk()) return status;
    status = tree.provider().pin(workspace.leafPageId, true, workspace.current);
    if (status.isOk()) status = TupleBTreeLeafPage.delete(
        workspace.current.page(), workspace.current.start(), workspace.pageScratch, 0,
        tree.schemaId(), tree.shape(), key, offset, length, workspace.page);
    return TupleBTreeProviderAccess.release(tree.provider(), workspace.current, status);
  }

  private static StatusCode prepare(
      TupleBTree tree, ByteBuffer key, int offset, int length,
      TupleBTreeTreeWorkspace workspace) {
    if (!tree.isValid(workspace)
        || !TupleKeyCodec.matchesPhysicalIndexKey(key, offset, length, tree.shape())
        || key == workspace.pageScratch) return StatusCode.INVALID_EXTERNAL_INPUT;
    return StatusCode.OK;
  }
}
