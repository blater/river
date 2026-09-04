package io.riverdb.storage.btree;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.btree.TupleBTreePageCodec;
import java.nio.ByteBuffer;

/** Root-to-leaf traversal with a caller-owned structurally bounded parent path. */
final class TupleBTreeTraversal {
  private TupleBTreeTraversal() { }

  static StatusCode physical(
      TupleBTree tree, ByteBuffer key, int offset, int length,
      TupleBTreeTreeWorkspace workspace, boolean recordPath) {
    return descend(tree, key, offset, length, 0, workspace, recordPath);
  }

  static StatusCode prefix(
      TupleBTree tree, ByteBuffer key, int offset, int length, int parts,
      TupleBTreeTreeWorkspace workspace) {
    return descend(tree, key, offset, length, parts, workspace, false);
  }

  static StatusCode upperPrefix(
      TupleBTree tree, ByteBuffer key, int offset, int length, int parts,
      TupleBTreeTreeWorkspace workspace) {
    return descend(tree, key, offset, length, -parts - 2, workspace, false);
  }

  static StatusCode leftmost(TupleBTree tree, TupleBTreeTreeWorkspace workspace) {
    return descend(tree, null, 0, 0, -1, workspace, false);
  }

  static StatusCode rightmost(TupleBTree tree, TupleBTreeTreeWorkspace workspace) {
    return descend(tree, null, 0, 0, Integer.MIN_VALUE, workspace, false);
  }

  private static StatusCode descend(
      TupleBTree tree, ByteBuffer key, int offset, int length, int prefixParts,
      TupleBTreeTreeWorkspace workspace, boolean recordPath) {
    if (!tree.isValid(workspace)) return StatusCode.INVALID_EXTERNAL_INPUT;
    workspace.resetPath();
    int pageId = tree.provider().rootPageId();
    if (!BTreeStructuralLimits.validPageId(pageId)) return StatusCode.CORRUPTION;
    for (int level = 0; BTreeStructuralLimits.canVisitLevel(level); level++) {
      StatusCode status = tree.provider().pin(pageId, false, workspace.current);
      if (!status.isOk()) return status;
      status = TupleBTreePageAdmission.validate(
          workspace.current.page(), workspace.current.start(),
          tree.schemaId(), tree.shape(), 0, workspace.page,
          tree.provider(), workspace.current);
      if (!status.isOk()) return release(tree, workspace, status);
      if (workspace.page.header.type() == TupleBTreePageCodec.TYPE_LEAF) {
        workspace.leafPageId = pageId;
        return release(tree, workspace, StatusCode.OK);
      }
      if (workspace.page.header.type() != TupleBTreePageCodec.TYPE_INTERNAL) {
        return release(tree, workspace, StatusCode.CORRUPTION);
      }
      if (recordPath) workspace.pathPageIds[workspace.pathDepth++] = pageId;
      int child = child(tree, workspace, key, offset, length, prefixParts);
      status = release(tree, workspace,
          BTreeStructuralLimits.validPageId(child) ? StatusCode.OK : StatusCode.CORRUPTION);
      if (!status.isOk()) return status;
      pageId = child;
    }
    return StatusCode.CORRUPTION;
  }

  private static int child(
      TupleBTree tree, TupleBTreeTreeWorkspace workspace,
      ByteBuffer key, int offset, int length, int prefixParts) {
    if (prefixParts == -1) return workspace.page.header.firstChildPageId();
    if (prefixParts == Integer.MIN_VALUE) {
      int count = workspace.page.header.entryCount();
      if (count == 0) return 0;
      TupleBTreePageSupport.readInternal(
          workspace.current.page(), workspace.current.start(), count - 1, workspace.page);
      return workspace.page.internal.rightChildPageId();
    }
    if (prefixParts < -1) return TupleBTreeInternalPrefixSearch.upperChildValidated(
        workspace.current.page(), workspace.current.start(),
        key, offset, length, -prefixParts - 2, workspace.page);
    if (prefixParts > 0) return TupleBTreeInternalPrefixSearch.childValidated(
        workspace.current.page(), workspace.current.start(),
        key, offset, length, prefixParts, workspace.page);
    return TupleBTreeInternalSearch.childForKeyValidated(
        workspace.current.page(), workspace.current.start(), key, offset, length,
        workspace.page);
  }

  private static StatusCode release(
      TupleBTree tree, TupleBTreeTreeWorkspace workspace, StatusCode status) {
    return TupleBTreeProviderAccess.release(tree.provider(), workspace.current, status);
  }
}
