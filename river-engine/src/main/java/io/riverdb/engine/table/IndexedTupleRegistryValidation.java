package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.btree.TupleIndexRootRecord;
import io.riverdb.format.catalog.CatalogKeyspace;
import io.riverdb.format.page.PageCodec;
import io.riverdb.storage.btree.BTreePage;
import io.riverdb.storage.heap.HeapPage;
import java.nio.ByteBuffer;

/** Enumerates durable registry heads and validates every owned tuple graph exactly once. */
final class IndexedTupleRegistryValidation {
  private final IndexedPageSet pages;
  private final IndexedTupleRegistryRowReader rows;
  private final IndexedTupleGraphValidation graphs;

  IndexedTupleRegistryValidation(IndexedPageSet pageSet, IndexedVersionState versions) {
    this(pageSet, versions, new PagedBooleanArray(IndexedTableLimits.MAX_PAGES));
  }

  IndexedTupleRegistryValidation(
      IndexedPageSet pageSet,
      IndexedVersionState versions,
      PagedBooleanArray visited) {
    pages = pageSet;
    rows = new IndexedTupleRegistryRowReader(pages, versions);
    graphs = new IndexedTupleGraphValidation(pages, visited);
  }

  StatusCode validate(int nextPageId, long rowCount) {
    graphs.reset();
    StatusCode status = StatusCode.OK;
    for (int pageId = IndexedTableKernel.INITIAL_LEAF_PAGE_ID;
        status.isOk() && pageId < nextPageId; pageId++) {
      if (!pages.isPresent(pageId)
          || pages.payloadKind(pageId) != PageCodec.PAYLOAD_KIND_SCALAR_BTREE) continue;
      status = pages.pinCurrentPage(pageId);
      if (!status.isOk()) return status;
      try {
        ByteBuffer page = pages.currentPayload(pageId);
        if (page == null) status = pages.lastStatus();
        else if (!HeapPage.isHeap(page) && BTreePage.type(page) == BTreePage.TYPE_LEAF) {
          status = validateLeaf(page, rowCount, nextPageId);
        }
      } finally {
        pages.unpinCurrentPage(pageId);
      }
    }
    return status.isOk() ? graphs.reconcile(nextPageId) : status;
  }

  private StatusCode validateLeaf(ByteBuffer leaf, long rowCount, int nextPageId) {
    for (int entry = 0; entry < BTreePage.entryCount(leaf); entry++) {
      if (BTreePage.spaceAt(leaf, entry) != CatalogKeyspace.INDEX_ROOT_SPACE) continue;
      long keyId = BTreePage.keyAt(leaf, entry);
      StatusCode status = rows.read(BTreePage.leafValueAt(leaf, entry), rowCount, keyId);
      if (!status.isOk()) return status;
      TupleIndexRootRecord root = rows.record();
      int state = root.state();
      if (state == io.riverdb.format.btree.TupleIndexRootRecordCodec.STATE_ABSENT
          || state == io.riverdb.format.btree.TupleIndexRootRecordCodec.STATE_BUILDING
              && root.rootPageId() == 0) continue;
      status = state == io.riverdb.format.btree.TupleIndexRootRecordCodec.STATE_DROPPING
              && root.rootPageId() == 0
          ? graphs.accountDetached(root.keyId(), nextPageId)
          : graphs.validate(
              root.rootPageId(), root.keyId(), root.schemaId(), rows.shape(),
              nextPageId);
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }
}
