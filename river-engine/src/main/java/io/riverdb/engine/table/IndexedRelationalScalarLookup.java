package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.storage.btree.BTreeLookupResult;
import io.riverdb.storage.btree.BTreePage;
import java.nio.ByteBuffer;

/** Reusable lookup over the scalar operation overlay. */
final class IndexedRelationalScalarLookup {
  private final IndexedTableKernel kernel;
  private final IndexedPageSet pages;
  private final BTreeLookupResult result = new BTreeLookupResult();
  private int leafPageId;

  IndexedRelationalScalarLookup(IndexedTableKernel table, IndexedPageSet pageSet) {
    kernel = table;
    pages = pageSet;
  }

  StatusCode find(long space, long key) {
    leafPageId = kernel.findOperationLeafPageId(space, key);
    if (leafPageId <= 0) return kernel.operationLookupStatus();
    ByteBuffer leaf = pages.operationPayload(leafPageId);
    return leaf == null
        ? pages.lastStatus() : BTreePage.lookupLeaf(leaf, space, key, result);
  }

  int leafPageId() { return leafPageId; }
  long rowId() { return result.rowId(); }
}
