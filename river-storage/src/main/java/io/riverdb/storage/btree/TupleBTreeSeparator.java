package io.riverdb.storage.btree;

import io.riverdb.base.error.StatusCode;

/** Copies a borrowed promoted separator into operation-owned scratch. */
final class TupleBTreeSeparator {
  private TupleBTreeSeparator() { }

  static StatusCode capture(TupleBTreeTreeWorkspace workspace) {
    int length = workspace.split.separatorLength();
    if (length <= 0 || length > workspace.keyScratch.limit()) {
      return StatusCode.INVARIANT_BROKEN;
    }
    if (workspace.split.separatorSource() != workspace.keyScratch
        || workspace.split.separatorOffset() != 0) {
      for (int index = 0; index < length; index++) {
        workspace.keyScratch.put(
            index,
            workspace.split.separatorSource().get(workspace.split.separatorOffset() + index));
      }
    }
    workspace.split.set(workspace.keyScratch, 0, length,
        workspace.split.leftCount(), workspace.split.rightCount());
    return StatusCode.OK;
  }
}
