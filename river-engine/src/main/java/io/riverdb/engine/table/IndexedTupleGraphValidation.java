package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.format.page.PageCodec;
import io.riverdb.storage.btree.TupleBTree;
import io.riverdb.storage.btree.BTreeStructuralLimits;
import io.riverdb.storage.btree.TupleBTreeTreeWorkspace;
import io.riverdb.storage.btree.TupleBTreeValidationResult;
import java.nio.ByteBuffer;

/** Validates READY tuple graphs and reconciles global tuple-page ownership. */
final class IndexedTupleGraphValidation {
  private final IndexedPageSet pages;
  private final IndexedTupleValidationProvider provider;
  private final TupleBTree tree;
  private final TupleBTreeTreeWorkspace workspace;
  private final TupleBTreeValidationResult result = new TupleBTreeValidationResult();

  IndexedTupleGraphValidation(IndexedPageSet pageSet) {
    this(pageSet, new PagedBooleanArray(IndexedTableLimits.MAX_PAGES));
  }

  IndexedTupleGraphValidation(IndexedPageSet pageSet, PagedBooleanArray visited) {
    pages = pageSet;
    provider = new IndexedTupleValidationProvider(pages, visited);
    tree = new TupleBTree(provider, 1, null);
    int height = BTreeStructuralLimits.MAXIMUM_LEVELS;
    workspace = new TupleBTreeTreeWorkspace(
        ByteBuffer.allocate(PageCodec.MAX_PAYLOAD_BYTES),
        ByteBuffer.allocate(io.riverdb.format.btree.TupleKeyCodec.MAX_PHYSICAL_INDEX_KEY_BYTES),
        new int[height], new int[height], new int[height]);
  }

  void reset() { provider.reset(); }

  StatusCode validate(
      int root, long keyId, long schemaId, TupleShape shape, int nextPageId) {
    StatusCode status = provider.configure(root, keyId, nextPageId);
    if (status.isOk()) status = tree.configure(provider, schemaId, shape);
    result.reset();
    return status.isOk() ? tree.validate(workspace, result) : status;
  }

  StatusCode reconcile(int nextPageId) {
    for (int pageId = 1; pageId <= pages.highestPageId(); pageId++) {
      if (pages.payloadKind(pageId) == PageCodec.PAYLOAD_KIND_TUPLE_BTREE
          && (!pages.isPresent(pageId) || !provider.reached(pageId))) {
        return StatusCode.CORRUPTION;
      }
    }
    return StatusCode.OK;
  }

  StatusCode accountDetached(long keyId, int nextPageId) {
    return provider.accountDetached(keyId, nextPageId);
  }
}
