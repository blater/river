package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.storage.btree.BTreePage;
import io.riverdb.storage.heap.HeapInsertResult;
import java.nio.ByteBuffer;

/** Stages one scalar mapping and its heap version under the logical page bound. */
final class IndexedRelationalScalarWriter {
  private final IndexedTableKernel kernel;
  private final IndexedPageSet pages;
  private final IndexedRelationalScalarLookup lookup;
  private final IndexedOperationPage leaf = new IndexedOperationPage();
  private final HeapInsertResult inserted = new HeapInsertResult();

  IndexedRelationalScalarWriter(IndexedTableKernel table, IndexedPageSet pageSet) {
    kernel = table;
    pages = pageSet;
    lookup = new IndexedRelationalScalarLookup(table, pageSet);
  }

  StatusCode stage(
      long space, long key, long previousRowId, ByteBuffer row, boolean deleted) {
    StatusCode found = lookup.find(space, key);
    if (previousRowId == 0 ? found != StatusCode.CONFLICT
        : !found.isOk() || lookup.rowId() != previousRowId) return StatusCode.CORRUPTION;
    StatusCode status = pages.pinScalarOperationPage(lookup.leafPageId(), true, leaf);
    if (status.isOk()) status = kernel.stageRelationalVersionRow(
        row, row.position(), row.remaining(), previousRowId, deleted, inserted);
    if (status.isOk()) status = previousRowId == 0
        ? BTreePage.insertLeaf(leaf.payload(), space, key, inserted.rowId())
        : BTreePage.updateLeaf(leaf.payload(), space, key, inserted.rowId());
    if (status == StatusCode.RESOURCE_EXHAUSTED && previousRowId == 0) {
      status = kernel.splitRelationalIndexLeaf(
          lookup.leafPageId(), leaf.payload(), space, key, inserted.rowId());
    }
    StatusCode released = leaf.attached() ? pages.releaseOperationPage(leaf) : StatusCode.OK;
    return status.isOk() ? released : status;
  }

  long rowId() { return inserted.rowId(); }
}
