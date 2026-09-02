package io.riverdb.storage.btree;

/** Child decoding and active-path cycle checks for graph validation. */
final class TupleBTreeGraphPages {
  private TupleBTreeGraphPages() { }

  static int child(TupleBTreeTreeWorkspace workspace, int ordinal) {
    if (ordinal == 0) return workspace.page.header.firstChildPageId();
    TupleBTreePageSupport.readInternal(
        workspace.current.page(), workspace.current.start(), ordinal - 1, workspace.page);
    return workspace.page.internal.rightChildPageId();
  }

  static boolean onPath(TupleBTreeTreeWorkspace workspace, int depth, int pageId) {
    for (int level = 0; level <= depth; level++) {
      if (workspace.pathPageIds[level] == pageId) return true;
    }
    return false;
  }
}
