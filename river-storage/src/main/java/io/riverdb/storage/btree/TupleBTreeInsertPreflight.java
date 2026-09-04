package io.riverdb.storage.btree;

import io.riverdb.base.error.StatusCode;
import java.nio.ByteBuffer;

/** Read-only exact insert admission over a provider-owned tuple tree. */
final class TupleBTreeInsertPreflight {
  private TupleBTreeInsertPreflight() { }

  static StatusCode plan(
      TupleBTree tree, ByteBuffer key, int offset, int length,
      TupleBTreeTreeWorkspace workspace, TupleBTreeInsertPreflightResult result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    StatusCode status = TupleBTreeKeyInput.copy(
        tree, key, offset, length, workspace);
    if (!status.isOk()) return status;
    status = TupleBTreeTraversal.physical(
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

}
