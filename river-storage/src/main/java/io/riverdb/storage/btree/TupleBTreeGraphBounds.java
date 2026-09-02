package io.riverdb.storage.btree;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.btree.TupleBTreePageCodec;
import io.riverdb.format.btree.TupleKeyCodec;

/** Parent-fence and ancestor-lower-bound validation for one pinned graph page. */
final class TupleBTreeGraphBounds {
  private TupleBTreeGraphBounds() { }

  static StatusCode validate(
      TupleBTree tree, TupleBTreeTreeWorkspace workspace, int depth) {
    if (depth == 0) {
      return workspace.page.header.highKeyLength() == 0
          ? StatusCode.OK : StatusCode.CORRUPTION;
    }
    StatusCode status = parentFence(tree, workspace, depth);
    if (!status.isOk() || workspace.page.header.type() != TupleBTreePageCodec.TYPE_LEAF
        || workspace.page.header.entryCount() == 0) return status;
    TupleBTreePageSupport.readLeaf(
        workspace.current.page(), workspace.current.start(), 0, workspace.page);
    int firstOffset = workspace.current.start() + workspace.page.leaf.keyOffset();
    int firstLength = workspace.page.leaf.keyLength();
    for (int level = 1; status.isOk() && level <= depth; level++) {
      int ordinal = workspace.pathChildOrdinals[level];
      if (ordinal > 0) status = lowerBound(
          tree, workspace, level - 1, ordinal - 1, firstOffset, firstLength);
    }
    return status;
  }

  private static StatusCode parentFence(
      TupleBTree tree, TupleBTreeTreeWorkspace workspace, int depth) {
    int parentId = workspace.pathPageIds[depth - 1];
    int ordinal = workspace.pathChildOrdinals[depth];
    StatusCode status = pinParent(tree, workspace, parentId);
    if (!status.isOk()) return status;
    int expectedOffset;
    int expectedLength;
    if (ordinal < workspace.otherPage.header.entryCount()) {
      TupleBTreePageSupport.readInternal(
          workspace.other.page(), workspace.other.start(), ordinal, workspace.otherPage);
      expectedOffset = workspace.other.start() + workspace.otherPage.internal.keyOffset();
      expectedLength = workspace.otherPage.internal.keyLength();
    } else {
      expectedOffset = workspace.other.start() + workspace.otherPage.header.highKeyOffset();
      expectedLength = workspace.otherPage.header.highKeyLength();
    }
    boolean same = sameFence(workspace, expectedOffset, expectedLength);
    return TupleBTreeProviderAccess.release(
        tree.provider(), workspace.other, same ? StatusCode.OK : StatusCode.CORRUPTION);
  }

  private static StatusCode lowerBound(
      TupleBTree tree, TupleBTreeTreeWorkspace workspace,
      int parentLevel, int separatorOrdinal, int firstOffset, int firstLength) {
    StatusCode status = pinParent(
        tree, workspace, workspace.pathPageIds[parentLevel]);
    if (!status.isOk()) return status;
    TupleBTreePageSupport.readInternal(
        workspace.other.page(), workspace.other.start(), separatorOrdinal, workspace.otherPage);
    int comparison = TupleKeyCodec.compare(
        workspace.current.page(), firstOffset, firstLength,
        workspace.other.page(),
        workspace.other.start() + workspace.otherPage.internal.keyOffset(),
        workspace.otherPage.internal.keyLength());
    return TupleBTreeProviderAccess.release(
        tree.provider(), workspace.other,
        comparison >= 0 ? StatusCode.OK : StatusCode.CORRUPTION);
  }

  private static StatusCode pinParent(
      TupleBTree tree, TupleBTreeTreeWorkspace workspace, int pageId) {
    StatusCode status = tree.provider().pin(pageId, false, workspace.other);
    if (status.isOk()) status = TupleBTreePageCodec.validate(
        workspace.other.page(), workspace.other.start(), tree.schemaId(), tree.shape(),
        workspace.otherPage.header);
    if (status.isOk() && workspace.otherPage.header.type()
        != TupleBTreePageCodec.TYPE_INTERNAL) status = StatusCode.CORRUPTION;
    return status.isOk() ? status
        : TupleBTreeProviderAccess.release(tree.provider(), workspace.other, status);
  }

  private static boolean sameFence(
      TupleBTreeTreeWorkspace workspace, int expectedOffset, int expectedLength) {
    int actualLength = workspace.page.header.highKeyLength();
    return actualLength == expectedLength && (actualLength == 0 || TupleKeyCodec.compare(
        workspace.current.page(),
        workspace.current.start() + workspace.page.header.highKeyOffset(), actualLength,
        workspace.other.page(), expectedOffset, expectedLength) == 0);
  }
}
