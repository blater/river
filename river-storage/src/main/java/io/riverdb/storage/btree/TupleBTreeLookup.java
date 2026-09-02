package io.riverdb.storage.btree;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.btree.TupleKeyCodec;
import java.nio.ByteBuffer;

/** Whole-tree exact lookup. */
final class TupleBTreeLookup {
  private TupleBTreeLookup() { }

  static StatusCode lookup(
      TupleBTree tree, ByteBuffer key, int offset, int length,
      TupleBTreeTreeWorkspace workspace, TupleBTreeLookupResult result) {
    if (result == null || !tree.isValid(workspace)
        || !TupleKeyCodec.matchesPhysicalIndexKey(key, offset, length, tree.shape())) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode status = TupleBTreeTraversal.physical(
        tree, key, offset, length, workspace, false);
    if (!status.isOk()) return status;
    status = tree.provider().pin(workspace.leafPageId, false, workspace.current);
    TupleBTreeLookupResult local = workspace.pageLookup;
    if (status.isOk()) status = TupleBTreeLeafSearch.lookupExact(
        workspace.current.page(), workspace.current.start(), tree.schemaId(), tree.shape(),
        key, offset, length, workspace.page, local, tree.provider(), workspace.current);
    if (status.isOk()) result.setTree(
        workspace.leafPageId, local.index(), local.keyOffset(), local.keyLength(),
        local.logicalRowId());
    return TupleBTreeProviderAccess.release(tree.provider(), workspace.current, status);
  }
}
