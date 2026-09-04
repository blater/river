package io.riverdb.storage.btree;

import io.riverdb.base.error.StatusCode;
import java.nio.ByteBuffer;

/** Whole-tree mutation admission and leaf dispatch; any non-OK result requires transaction abort. */
final class TupleBTreeMutation {
  private TupleBTreeMutation() { }

  static StatusCode insert(
      TupleBTree tree, ByteBuffer key, int offset, int length,
      TupleBTreeTreeWorkspace workspace) {
    StatusCode status = TupleBTreeKeyInput.copy(
        tree, key, offset, length, workspace);
    if (!status.isOk()) return status;
    key = workspace.keyScratch;
    offset = 0;
    int originalRoot = tree.provider().rootPageId();
    status = TupleBTreeTraversal.physical(tree, key, offset, length, workspace, true);
    if (!status.isOk()) return status;
    status = tree.provider().pin(workspace.leafPageId, true, workspace.current);
    if (!status.isOk()) return status;
    status = TupleBTreeLeafPage.insertBorrowed(
        workspace.current.page(), workspace.current.start(), tree.schemaId(), tree.shape(),
        key, offset, length, workspace.page, tree.provider(), workspace.current);
    if (status != StatusCode.RESOURCE_EXHAUSTED) {
      if (status.isOk()) status = sealCanonicalLeaf(tree, workspace);
      return TupleBTreeProviderAccess.release(tree.provider(), workspace.current, status);
    }
    return TupleBTreeSplitPropagation.leaf(
        tree, key, offset, length, originalRoot, workspace);
  }

  static StatusCode delete(
      TupleBTree tree, ByteBuffer key, int offset, int length,
      TupleBTreeTreeWorkspace workspace) {
    StatusCode status = TupleBTreeKeyInput.copy(
        tree, key, offset, length, workspace);
    if (!status.isOk()) return status;
    key = workspace.keyScratch;
    offset = 0;
    status = TupleBTreeTraversal.physical(tree, key, offset, length, workspace, false);
    if (!status.isOk()) return status;
    status = tree.provider().pin(workspace.leafPageId, true, workspace.current);
    if (status.isOk()) status = TupleBTreeLeafPage.deleteBorrowed(
        workspace.current.page(), workspace.current.start(), tree.schemaId(), tree.shape(),
        key, offset, length, workspace.page, tree.provider(), workspace.current);
    if (status.isOk()) status = sealCanonicalLeaf(tree, workspace);
    return TupleBTreeProviderAccess.release(tree.provider(), workspace.current, status);
  }

  private static StatusCode sealCanonicalLeaf(
      TupleBTree tree, TupleBTreeTreeWorkspace workspace) {
    return tree.provider().sealCanonicalMutation(
        workspace.current, tree.schemaId(), tree.shape().descriptorHash(),
        io.riverdb.format.btree.TupleBTreePageCodec.TYPE_LEAF,
        workspace.current.validation());
  }
}
