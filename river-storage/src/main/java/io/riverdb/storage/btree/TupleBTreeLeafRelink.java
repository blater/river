package io.riverdb.storage.btree;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.btree.TupleBTreePageCodec;

/** Stages the reciprocal neighbor update required by one leaf split. */
final class TupleBTreeLeafRelink {
  private TupleBTreeLeafRelink() { }

  static StatusCode replaceLeft(
      TupleBTree tree, int pageId, int expectedLeft, int replacementLeft,
      TupleBTreeTreeWorkspace workspace) {
    StatusCode status = tree.provider().pin(pageId, true, workspace.current);
    if (status.isOk()) status = TupleBTreePageCodec.replaceLeftSibling(
        workspace.current.page(), workspace.current.start(), expectedLeft, replacementLeft);
    return TupleBTreeProviderAccess.release(tree.provider(), workspace.current, status);
  }
}
