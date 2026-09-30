package io.riverdb.storage.btree;

import io.riverdb.base.error.StatusCode;
import java.nio.ByteBuffer;

/** Whole-tree mutation admission and leaf dispatch; any non-OK result requires transaction abort. */
final class TupleBTreeMutation {
  private TupleBTreeMutation() { }

  static StatusCode insert(
      TupleBTree tree, ByteBuffer key, int offset, int length,
      TupleBTreeTreeWorkspace workspace) {
    return insert(tree, key, offset, length, null, 0, 0, 0, 0, 0, workspace);
  }

  static StatusCode insert(
      TupleBTree tree, ByteBuffer key, int offset, int length,
      ByteBuffer value, int valueOffset, int valueLength,
      int overflowPageId, long overflowGeneration, long modificationSequence,
      TupleBTreeTreeWorkspace workspace) {
    if (overflowPageId == 0
        && !io.riverdb.format.btree.TupleBTreePageCodec.inlineEligible(length, valueLength)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = TupleBTreeKeyInput.copy(
        tree, key, offset, length, workspace);
    if (!status.isOk()) return status;
    key = workspace.keyScratch;
    offset = 0;
    while (true) {
      int originalRoot = tree.provider().rootPageId();
      status = TupleBTreeTraversal.physical(tree, key, offset, length, workspace, true);
      if (!status.isOk()) return status;
      status = tree.provider().pin(workspace.leafPageId, true, workspace.current);
      if (!status.isOk()) return status;
      status = TupleBTreeLeafPage.insertBorrowed(
          workspace.current.page(), workspace.current.start(), tree.schemaId(), tree.shape(),
          key, offset, length, value, valueOffset, valueLength,
          overflowPageId, overflowGeneration, modificationSequence,
          workspace.page, tree.provider(), workspace.current);
      if (status != StatusCode.RESOURCE_EXHAUSTED) {
        if (status.isOk()) status = sealCanonicalLeaf(tree, workspace);
        return TupleBTreeProviderAccess.release(tree.provider(), workspace.current, status);
      }
      for (int index = 0; index < length; index++) {
        workspace.retryKeyScratch.put(index, key.get(index));
      }
      status = TupleBTreeSplitPropagation.leaf(
          tree, key, offset, length, value, valueOffset, valueLength,
          overflowPageId, overflowGeneration, modificationSequence,
          originalRoot, workspace);
      if (status != StatusCode.RETRY || !workspace.retryInsertionAfterSplit) return status;
      status = TupleBTreeKeyInput.copy(tree, workspace.retryKeyScratch, 0, length, workspace);
      if (!status.isOk()) return status;
    }
  }

  static StatusCode delete(
      TupleBTree tree, ByteBuffer key, int offset, int length,
      TupleBTreeTreeWorkspace workspace) {
    workspace.clearRemovedValue();
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

  static StatusCode replaceValue(
      TupleBTree tree, ByteBuffer key, int offset, int length,
      ByteBuffer value, int valueOffset, int valueLength,
      int overflowPageId, long overflowGeneration, long modificationSequence,
      TupleBTreeTreeWorkspace workspace) {
    workspace.clearRemovedValue();
    if (overflowPageId == 0
        && !io.riverdb.format.btree.TupleBTreePageCodec.inlineEligible(length, valueLength)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = TupleBTreeKeyInput.copy(tree, key, offset, length, workspace);
    if (!status.isOk()) return status;
    key = workspace.keyScratch;
    offset = 0;
    status = TupleBTreeTraversal.physical(tree, key, offset, length, workspace, false);
    if (!status.isOk()) return status;
    status = tree.provider().pin(workspace.leafPageId, true, workspace.current);
    if (!status.isOk()) return status;
    status = TupleBTreeLeafPage.replaceBorrowed(
        workspace.current.page(), workspace.current.start(), tree.schemaId(), tree.shape(),
        key, offset, length, value, valueOffset, valueLength,
        overflowPageId, overflowGeneration, modificationSequence,
        workspace.page, tree.provider(), workspace.current);
    if (status == StatusCode.RESOURCE_EXHAUSTED) {
      for (int index = 0; index < length; index++) {
        workspace.retryKeyScratch.put(index, key.get(index));
      }
      status = TupleBTreeProviderAccess.release(tree.provider(), workspace.current, status);
      if (status != StatusCode.RESOURCE_EXHAUSTED) return status;
      status = delete(tree, workspace.retryKeyScratch, 0, length, workspace);
      return status.isOk() ? insert(tree, workspace.retryKeyScratch, 0, length,
          value, valueOffset, valueLength, overflowPageId, overflowGeneration,
          modificationSequence, workspace) : status;
    }
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
