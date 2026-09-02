package io.riverdb.storage.btree;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.btree.TupleBTreePageCodec;
import io.riverdb.format.btree.TupleKeyCodec;

/** Bottom-up exact parent cascade for read-only insert preflight. */
final class TupleBTreeInsertPreflightParents {
  private TupleBTreeInsertPreflightParents() { }

  static StatusCode plan(
      TupleBTree tree, TupleBTreeTreeWorkspace workspace,
      TupleBTreeInsertPreflightResult result) {
    int separatorLength = workspace.split.separatorLength();
    for (int parent = workspace.pathDepth - 1; parent >= 0; parent--) {
      StatusCode status = tree.provider().pin(
          workspace.pathPageIds[parent], false, workspace.current);
      if (!status.isOk()) return failure(result, status);
      status = parent(tree, workspace, result, separatorLength);
      if (status.isOk()) separatorLength = workspace.split.separatorLength();
      status = TupleBTreeProviderAccess.release(tree.provider(), workspace.current, status);
      if (!status.isOk()) return failure(result, status);
      if (result.splitLevelCount() <= workspace.pathDepth - parent) return StatusCode.OK;
    }
    if (workspace.pathDepth + 1 >= TupleBTreeTreeWorkspace.MAXIMUM_HEIGHT) {
      return failure(result, StatusCode.RESOURCE_EXHAUSTED);
    }
    result.set(false, true, result.newPageCount() + 1,
        result.changedPageCount() + 1, result.splitLevelCount(), workspace.pathDepth + 2);
    return StatusCode.OK;
  }

  private static StatusCode parent(
      TupleBTree tree, TupleBTreeTreeWorkspace workspace,
      TupleBTreeInsertPreflightResult result, int separatorLength) {
    StatusCode status = TupleBTreePageSupport.validate(
        workspace.current.page(), workspace.current.start(), tree.schemaId(), tree.shape(),
        TupleBTreePageCodec.TYPE_INTERNAL, workspace.page);
    if (!status.isOk()) return status;
    int insertion = TupleBTreePageSupport.lowerBoundInternal(
        workspace.current.page(), workspace.current.start(), workspace.keyScratch, 0,
        separatorLength, workspace.page);
    if (equalAt(workspace, insertion, separatorLength)) return StatusCode.CORRUPTION;
    if (TupleBTreePageOccupancy.acceptsInternal(
        workspace.current.page(), workspace.current.start(),
        separatorLength, workspace.page)) {
      result.set(false, false, result.newPageCount(), result.changedPageCount() + 1,
          result.splitLevelCount(), workspace.pathDepth + 1);
      return StatusCode.OK;
    }
    return split(workspace, result, insertion, separatorLength);
  }

  private static StatusCode split(
      TupleBTreeTreeWorkspace workspace, TupleBTreeInsertPreflightResult result,
      int insertion, int separatorLength) {
    int promoted = TupleBTreeInternalSplitPoint.choose(
        workspace.current.page(), workspace.current.start(), separatorLength,
        insertion, workspace.page);
    if (promoted < 0) return StatusCode.RESOURCE_EXHAUSTED;
    int promotedLength = TupleBTreePreflightSeparator.internal(
        workspace.current.page(), workspace.current.start(), insertion, promoted,
        workspace.keyScratch, separatorLength, workspace.page);
    if (promotedLength <= 0) return StatusCode.INVARIANT_BROKEN;
    workspace.split.set(workspace.keyScratch, 0, promotedLength, promoted,
        workspace.page.header.entryCount() - promoted);
    result.set(false, false, result.newPageCount() + 1,
        result.changedPageCount() + 2, result.splitLevelCount() + 1,
        workspace.pathDepth + 1);
    return StatusCode.OK;
  }

  private static boolean equalAt(
      TupleBTreeTreeWorkspace workspace, int insertion, int separatorLength) {
    if (insertion >= workspace.page.header.entryCount()) return false;
    TupleBTreePageSupport.readInternal(
        workspace.current.page(), workspace.current.start(), insertion, workspace.page);
    return TupleKeyCodec.compare(
        workspace.current.page(),
        workspace.current.start() + workspace.page.internal.keyOffset(),
        workspace.page.internal.keyLength(), workspace.keyScratch, 0, separatorLength) == 0;
  }

  private static StatusCode failure(
      TupleBTreeInsertPreflightResult result, StatusCode status) {
    result.reset();
    return status;
  }
}
