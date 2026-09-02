package io.riverdb.storage.btree;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.btree.TupleBTreePageCodec;

/** Initializes one empty provider-owned tuple tree. */
final class TupleBTreeInitialization {
  private TupleBTreeInitialization() { }

  static StatusCode initialize(TupleBTree tree, TupleBTreeTreeWorkspace workspace) {
    if (!tree.isValid(workspace) || tree.provider().rootPageId() != 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    TupleBTreePageReference page = workspace.current;
    StatusCode status = tree.provider().allocate(page);
    if (status.isOk() && (!page.isAttached() || !page.isWritable())) {
      status = StatusCode.INVARIANT_BROKEN;
    }
    if (status.isOk()) status = TupleBTreePageCodec.initialize(
        page.page(), page.start(), TupleBTreePageCodec.TYPE_LEAF, 0,
        tree.shape(), tree.schemaId(), null, 0, 0);
    int rootPageId = page.pageId();
    status = TupleBTreeProviderAccess.release(tree.provider(), page, status);
    return status.isOk() ? tree.provider().replaceRoot(0, rootPageId) : status;
  }
}
