package io.riverdb.storage.btree;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.btree.TupleBTreePageCodec;

/** Iterative allocation-free validation of a tuple-tree page graph and leaf chain. */
final class TupleBTreeGraphValidation {
  private TupleBTreeGraphValidation() { }

  static StatusCode validate(
      TupleBTree tree, TupleBTreeTreeWorkspace workspace, TupleBTreeValidationResult result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (tree == null || !tree.isValid(workspace)) return StatusCode.INVALID_EXTERNAL_INPUT;
    workspace.resetPath();
    int root = tree.provider().rootPageId();
    if (!TupleBTreeStructure.validPageId(root)) return StatusCode.CORRUPTION;
    workspace.pathPageIds[0] = root;
    workspace.pathChildOrdinals[0] = -1;
    workspace.pathNextChildOrdinals[0] = -1;
    int depth = 0;
    while (depth >= 0) {
      int pageId = workspace.pathPageIds[depth];
      StatusCode status = pinAndValidate(tree, workspace, pageId);
      if (!status.isOk()) return status;
      boolean entering = workspace.pathNextChildOrdinals[depth] < 0;
      if (entering) {
        status = tree.provider().visit(pageId);
        if (status.isOk()) status = TupleBTreeGraphBounds.validate(tree, workspace, depth);
        if (!status.isOk()) return release(tree, workspace, status);
        workspace.graph.pages++;
        workspace.graph.height = Math.max(workspace.graph.height, depth + 1);
        if (workspace.page.header.type() == TupleBTreePageCodec.TYPE_LEAF) {
          status = TupleBTreeGraphLeaves.enter(workspace, depth, pageId);
          status = release(tree, workspace, status);
          if (!status.isOk()) return status;
          depth--;
          continue;
        }
        if (workspace.page.header.type() != TupleBTreePageCodec.TYPE_INTERNAL
            || workspace.page.header.entryCount() == 0) {
          return release(tree, workspace, StatusCode.CORRUPTION);
        }
        workspace.pathNextChildOrdinals[depth] = 0;
      }
      int ordinal = workspace.pathNextChildOrdinals[depth];
      if (ordinal > workspace.page.header.entryCount()) {
        status = release(tree, workspace, StatusCode.OK);
        if (!status.isOk()) return status;
        depth--;
        continue;
      }
      int child = TupleBTreeGraphPages.child(workspace, ordinal);
      workspace.pathNextChildOrdinals[depth] = ordinal + 1;
      status = release(tree, workspace,
          TupleBTreeStructure.validPageId(child) ? StatusCode.OK : StatusCode.CORRUPTION);
      if (!status.isOk()) return status;
      if (!TupleBTreeStructure.canDescendFrom(depth)
          || TupleBTreeGraphPages.onPath(workspace, depth, child)) {
        return StatusCode.CORRUPTION;
      }
      depth++;
      workspace.pathPageIds[depth] = child;
      workspace.pathChildOrdinals[depth] = ordinal;
      workspace.pathNextChildOrdinals[depth] = -1;
    }
    if (workspace.graph.expectedLeaf != 0 || workspace.graph.leaves == 0) {
      return StatusCode.CORRUPTION;
    }
    result.set(
        workspace.graph.height,
        workspace.graph.pages,
        workspace.graph.leaves,
        workspace.graph.entries);
    return StatusCode.OK;
  }

  private static StatusCode pinAndValidate(
      TupleBTree tree, TupleBTreeTreeWorkspace workspace, int pageId) {
    StatusCode status = tree.provider().pin(pageId, false, workspace.current);
    if (status.isOk()) status = TupleBTreePageAdmission.validate(
        workspace.current.page(), workspace.current.start(),
        tree.schemaId(), tree.shape(), 0, workspace.page,
        tree.provider(), workspace.current);
    return status.isOk() ? status
        : TupleBTreeProviderAccess.release(tree.provider(), workspace.current, status);
  }

  private static StatusCode release(
      TupleBTree tree, TupleBTreeTreeWorkspace workspace, StatusCode status) {
    return TupleBTreeProviderAccess.release(tree.provider(), workspace.current, status);
  }
}
