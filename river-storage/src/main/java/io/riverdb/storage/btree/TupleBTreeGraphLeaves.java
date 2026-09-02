package io.riverdb.storage.btree;

import io.riverdb.base.error.StatusCode;

/** Validates and accumulates one leaf in depth-first graph order. */
final class TupleBTreeGraphLeaves {
  private TupleBTreeGraphLeaves() { }

  static StatusCode enter(
      TupleBTreeTreeWorkspace workspace, int depth, int pageId) {
    TupleBTreeGraphState state = workspace.graph;
    if ((state.leafDepth >= 0 && state.leafDepth != depth)
        || (state.leaves > 0 && state.expectedLeaf != pageId)
        || workspace.page.header.leftSiblingPageId() != state.previousLeaf) {
      return StatusCode.CORRUPTION;
    }
    if (state.leafDepth < 0) state.leafDepth = depth;
    state.expectedLeaf = workspace.page.header.rightSiblingPageId();
    state.previousLeaf = pageId;
    state.leaves++;
    state.entries += workspace.page.header.entryCount();
    return StatusCode.OK;
  }
}
