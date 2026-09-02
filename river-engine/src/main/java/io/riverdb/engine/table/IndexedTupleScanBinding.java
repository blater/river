package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.format.btree.TupleKeyCodec;
import io.riverdb.format.page.PageCodec;
import io.riverdb.storage.btree.TupleBTree;
import io.riverdb.storage.btree.TupleBTreeCursor;
import io.riverdb.storage.btree.TupleBTreeScanBounds;
import io.riverdb.storage.btree.TupleBTreeTreeWorkspace;
import java.nio.ByteBuffer;

/** Binds one persistent cursor to its page provider and coherent durable root. */
final class IndexedTupleScanBinding {
  private final TupleBTreeTreeWorkspace workspace;
  private IndexedTupleProbePageProvider provider;
  private IndexedTupleRootSnapshot root;
  private TupleBTree tree;
  private IndexedPageSet pages;

  IndexedTupleScanBinding() {
    int height = TupleBTreeTreeWorkspace.MAXIMUM_HEIGHT;
    workspace = new TupleBTreeTreeWorkspace(
        ByteBuffer.allocate(PageCodec.MAX_PAYLOAD_BYTES),
        ByteBuffer.allocate(TupleKeyCodec.MAX_PHYSICAL_INDEX_KEY_BYTES),
        new int[height], new int[height], new int[height]);
  }

  StatusCode open(
      IndexedTableKernel kernel, IndexedPageSet pageSet,
      long visible, long current, long owner, long keyId, long schemaId,
      long privateOwner,
      TupleShape shape, TupleBTreeScanBounds bounds, TupleBTreeCursor cursor) {
    StatusCode status = bind(kernel, pageSet);
    if (status.isOk()) status = root.load(privateOwner > 0 ? current : visible, keyId);
    boolean matches = privateOwner > 0
        ? root.matchesBuilding(owner, keyId, schemaId, privateOwner, shape)
        : root.matches(owner, keyId, schemaId, shape);
    if (status.isOk() && !matches) {
      status = StatusCode.CORRUPTION;
    }
    if (status.isOk()) status = provider.configure(
        root.rootPageId(), keyId, kernel.nextPageId(), root.generation(),
        privateOwner > 0 ? current : visible);
    if (status.isOk()) status = tree.configure(provider, schemaId, shape);
    return status.isOk() ? cursor.open(tree, bounds, workspace) : status;
  }

  private StatusCode bind(IndexedTableKernel kernel, IndexedPageSet pageSet) {
    if (provider != null) return pages == pageSet
        ? StatusCode.OK : StatusCode.INVALID_EXTERNAL_INPUT;
    try {
      pages = pageSet;
      provider = new IndexedTupleProbePageProvider(pageSet);
      root = new IndexedTupleRootSnapshot(kernel);
      tree = new TupleBTree(provider, 1, null);
      return StatusCode.OK;
    } catch (OutOfMemoryError failure) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }
}
