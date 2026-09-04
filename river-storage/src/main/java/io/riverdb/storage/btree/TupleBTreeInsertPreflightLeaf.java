package io.riverdb.storage.btree;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.btree.TupleBTreePageCodec;
import io.riverdb.format.btree.TupleKeyCodec;

/** Exact leaf outcome for read-only insert preflight. */
final class TupleBTreeInsertPreflightLeaf {
  private TupleBTreeInsertPreflightLeaf() { }

  static StatusCode plan(
      TupleBTree tree, int keyLength, TupleBTreeTreeWorkspace workspace,
      TupleBTreeInsertPreflightResult result) {
    if (workspace.current.page() == workspace.pageScratch
        || workspace.current.page() == workspace.keyScratch) {
      return StatusCode.INVARIANT_BROKEN;
    }
    StatusCode status = TupleBTreePageAdmission.validate(
        workspace.current.page(), workspace.current.start(), tree.schemaId(), tree.shape(),
        TupleBTreePageCodec.TYPE_LEAF, workspace.page,
        tree.provider(), workspace.current);
    if (!status.isOk()) return status;
    int insertion = TupleBTreePageSupport.lowerBoundLeaf(
        workspace.current.page(), workspace.current.start(), workspace.keyScratch, 0,
        keyLength, workspace.page);
    if (insertion < 0) return StatusCode.INVARIANT_BROKEN;
    int equality = equalAt(workspace, insertion, keyLength);
    if (equality < 0) return StatusCode.INVARIANT_BROKEN;
    if (equality > 0) {
      result.set(true, false, 0, 0, 0, workspace.pathDepth + 1);
      return StatusCode.OK;
    }
    if (TupleBTreePageOccupancy.accepts(keyLength, workspace.page)) {
      result.set(false, false, 0, 1, 0, workspace.pathDepth + 1);
      return StatusCode.OK;
    }
    int splitAt = TupleBTreeLeafSplitPoint.choose(
        workspace.current.page(), workspace.current.start(), workspace.keyScratch, 0,
        keyLength, insertion, workspace.page);
    if (splitAt < 0) return StatusCode.INVARIANT_BROKEN;
    if (splitAt == 0) return StatusCode.RESOURCE_EXHAUSTED;
    int separatorLength = TupleBTreePreflightSeparator.leaf(
        workspace.current.page(), workspace.current.start(), insertion, splitAt,
        workspace.keyScratch, keyLength, workspace.page);
    if (separatorLength <= 0) return StatusCode.INVARIANT_BROKEN;
    workspace.split.set(workspace.keyScratch, 0, separatorLength, splitAt,
        workspace.page.header.entryCount() + 1 - splitAt);
    int changedPages = workspace.page.header.rightSiblingPageId() == 0 ? 2 : 3;
    result.set(false, false, 1, changedPages, 1, workspace.pathDepth + 1);
    return StatusCode.OK;
  }

  private static int equalAt(
      TupleBTreeTreeWorkspace workspace, int insertion, int keyLength) {
    if (insertion >= workspace.page.header.entryCount()) return 0;
    if (!TupleBTreePageSupport.readLeaf(
        workspace.current.page(), workspace.current.start(), insertion, workspace.page)) {
      return -1;
    }
    return TupleKeyCodec.compare(
        workspace.current.page(),
        workspace.current.start() + workspace.page.leaf.keyOffset(),
        workspace.page.leaf.keyLength(), workspace.keyScratch, 0, keyLength) == 0 ? 1 : 0;
  }
}
