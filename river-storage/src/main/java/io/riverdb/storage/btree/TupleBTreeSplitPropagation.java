package io.riverdb.storage.btree;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.btree.TupleBTreePageCodec;
import java.nio.ByteBuffer;

/** Leaf split and bottom-up separator propagation. */
final class TupleBTreeSplitPropagation {
  private TupleBTreeSplitPropagation() { }

  static StatusCode leaf(
      TupleBTree tree, ByteBuffer key, int offset, int length,
      int originalRoot, TupleBTreeTreeWorkspace workspace) {
    StatusCode status = tree.provider().allocate(workspace.other);
    int leftPageId = workspace.leafPageId;
    int oldRightPageId = workspace.page.header.rightSiblingPageId();
    if (status.isOk()) status = validBuffers(workspace);
    if (status.isOk()) status = TupleBTreeLeafPage.splitInsert(
        workspace.current.page(), workspace.current.start(),
        workspace.pageScratch, 0, workspace.other.page(), workspace.other.start(),
        leftPageId, workspace.other.pageId(), tree.schemaId(), tree.shape(),
        key, offset, length, workspace.page, workspace.split);
    if (status.isOk()) {
      TupleBTreePageSupport.copyPayload(
          workspace.pageScratch, 0, workspace.current.page(), workspace.current.start());
      status = TupleBTreeSeparator.capture(workspace);
    }
    int rightPageId = workspace.other.pageId();
    status = releaseSplit(tree, workspace, status);
    if (status.isOk() && oldRightPageId > 0) status = TupleBTreeLeafRelink.replaceLeft(
        tree, oldRightPageId, leftPageId, rightPageId, workspace);
    return status.isOk() ? parents(
        tree, leftPageId, rightPageId, originalRoot, workspace) : status;
  }

  private static StatusCode parents(
      TupleBTree tree, int leftPageId, int rightPageId,
      int originalRoot, TupleBTreeTreeWorkspace workspace) {
    for (int parent = workspace.pathDepth - 1; parent >= 0; parent--) {
      int parentPageId = workspace.pathPageIds[parent];
      StatusCode status = tree.provider().pin(parentPageId, true, workspace.current);
      if (!status.isOk()) return status;
      status = TupleBTreeInternalPage.insert(
          workspace.current.page(), workspace.current.start(), workspace.pageScratch, 0,
          tree.schemaId(), tree.shape(), workspace.keyScratch, 0,
          workspace.split.separatorLength(), rightPageId, workspace.page);
      if (status != StatusCode.RESOURCE_EXHAUSTED) {
        status = TupleBTreeProviderAccess.release(tree.provider(), workspace.current, status);
        return status;
      }
      status = splitInternal(tree, workspace, rightPageId);
      leftPageId = parentPageId;
      rightPageId = workspace.propagatedRightPageId;
      if (!status.isOk()) return status;
    }
    return newRoot(tree, leftPageId, rightPageId, originalRoot, workspace);
  }

  private static StatusCode splitInternal(
      TupleBTree tree, TupleBTreeTreeWorkspace workspace, int rightChildPageId) {
    StatusCode status = tree.provider().allocate(workspace.other);
    if (status.isOk()) status = validBuffers(workspace);
    if (status.isOk()) status = TupleBTreeInternalPage.splitInsert(
        workspace.current.page(), workspace.current.start(),
        workspace.pageScratch, 0, workspace.other.page(), workspace.other.start(),
        tree.schemaId(), tree.shape(), workspace.keyScratch, 0,
        workspace.split.separatorLength(), rightChildPageId, workspace.page, workspace.split);
    if (status.isOk()) {
      workspace.propagatedRightPageId = workspace.other.pageId();
      status = TupleBTreeSeparator.capture(workspace);
    }
    if (status.isOk()) {
      TupleBTreePageSupport.copyPayload(
          workspace.pageScratch, 0, workspace.current.page(), workspace.current.start());
    }
    return releaseSplit(tree, workspace, status);
  }

  private static StatusCode newRoot(
      TupleBTree tree, int leftPageId, int rightPageId,
      int originalRoot, TupleBTreeTreeWorkspace workspace) {
    if (workspace.pathDepth + 1 >= TupleBTreeTreeWorkspace.MAXIMUM_HEIGHT) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = tree.provider().allocate(workspace.current);
    if (status.isOk()) status = TupleBTreePageCodec.initialize(
        workspace.current.page(), workspace.current.start(),
        TupleBTreePageCodec.TYPE_INTERNAL, leftPageId,
        tree.shape(), tree.schemaId(), null, 0, 0);
    if (status.isOk()) status = TupleBTreePageCodec.appendInternal(
        workspace.current.page(), workspace.current.start(), tree.shape(),
        workspace.keyScratch, 0, workspace.split.separatorLength(), rightPageId);
    int rootPageId = workspace.current.pageId();
    status = TupleBTreeProviderAccess.release(tree.provider(), workspace.current, status);
    return status.isOk()
        ? tree.provider().replaceRoot(originalRoot, rootPageId) : status;
  }

  private static StatusCode validBuffers(TupleBTreeTreeWorkspace workspace) {
    return !workspace.other.isAttached() || !workspace.other.isWritable()
        || workspace.current.page() == workspace.other.page()
        || workspace.current.page() == workspace.pageScratch
        || workspace.other.page() == workspace.pageScratch
        ? StatusCode.INVARIANT_BROKEN : StatusCode.OK;
  }

  private static StatusCode releaseSplit(
      TupleBTree tree, TupleBTreeTreeWorkspace workspace, StatusCode status) {
    status = TupleBTreeProviderAccess.release(tree.provider(), workspace.other, status);
    return TupleBTreeProviderAccess.release(tree.provider(), workspace.current, status);
  }
}
