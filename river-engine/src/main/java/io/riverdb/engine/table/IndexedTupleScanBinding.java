package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.format.btree.TupleKeyCodec;
import io.riverdb.format.btree.TupleRowOverflowCodec;
import io.riverdb.format.btree.TupleRowOverflowHeader;
import io.riverdb.format.page.PageCodec;
import io.riverdb.storage.btree.TupleBTree;
import io.riverdb.storage.btree.TupleBTreeCursor;
import io.riverdb.storage.btree.TupleBTreeScanBounds;
import io.riverdb.storage.btree.BTreeStructuralLimits;
import io.riverdb.storage.btree.TupleBTreeTreeWorkspace;
import java.nio.ByteBuffer;

/** Binds one persistent cursor to its page provider and coherent durable root. */
final class IndexedTupleScanBinding {
  private final TupleBTreeTreeWorkspace workspace;
  private IndexedTupleProbePageProvider provider;
  private IndexedTupleRootSnapshot root;
  private TupleBTree tree;
  private IndexedPageSet pages;
  private final IndexedPageGenerationPin overflowPin = new IndexedPageGenerationPin();
  private final TupleRowOverflowHeader overflowHeader = new TupleRowOverflowHeader();
  private long visibleCommitSequence;
  private long ownerKeyId;

  IndexedTupleScanBinding() {
    int height = BTreeStructuralLimits.MAXIMUM_LEVELS;
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
    if (status.isOk()) status = configure(
        kernel, visible, current, owner, keyId, schemaId, privateOwner, shape);
    return status.isOk() ? cursor.open(tree, bounds, workspace) : status;
  }

  private StatusCode configure(
      IndexedTableKernel kernel, long visible, long current, long owner,
      long keyId, long schemaId, long privateOwner, TupleShape shape) {
    long snapshot = privateOwner > 0 ? current : visible;
    StatusCode status = root.load(snapshot, keyId, privateOwner == 0);
    boolean matches = privateOwner > 0
        ? root.matchesBuilding(owner, keyId, schemaId, privateOwner, shape)
        : root.matches(owner, keyId, schemaId, shape);
    if (status.isOk() && !matches) status = StatusCode.CORRUPTION;
    if (status.isOk()) status = provider.configure(
        root.rootPageId(), keyId, kernel.nextPageId(), root.generation(), snapshot);
    if (status.isOk()) status = tree.configure(provider, schemaId, shape);
    if (status.isOk()) {
      visibleCommitSequence = snapshot;
      ownerKeyId = keyId;
    }
    return status;
  }

  StatusCode bindOverflow(IndexedTupleScanResult result) {
    if (result.overflowPageId() == 0) return StatusCode.OK;
    StatusCode status = pages.pinPageAt(
        result.overflowPageId(), visibleCommitSequence, overflowPin);
    if (!status.isOk()) return status;
    if (overflowPin.payloadKind() != PageCodec.PAYLOAD_KIND_TUPLE_OVERFLOW
        || overflowPin.ownerKeyId() != ownerKeyId
        || overflowPin.durableGeneration() != result.overflowGeneration()) {
      status = StatusCode.CORRUPTION;
    } else {
      status = TupleRowOverflowCodec.validate(
          overflowPin.payload(), 0, result.logicalRowId(), overflowHeader);
      if (status.isOk() && overflowHeader.valueLength() != result.valueLength()) {
        status = StatusCode.CORRUPTION;
      }
    }
    if (status.isOk()) {
      result.bindOverflow(overflowPin.payload(), TupleRowOverflowCodec.HEADER_BYTES);
      return status;
    }
    StatusCode released = releaseOverflow();
    return released.isOk() ? status : released;
  }

  StatusCode releaseOverflow() {
    return overflowPin.active() ? pages.unpinPage(overflowPin) : StatusCode.OK;
  }

  long observedCommitSequence() { return root.observedCommitSequence(); }

  private StatusCode bind(IndexedTableKernel kernel, IndexedPageSet pageSet) {
    if (provider != null && pages == pageSet) return StatusCode.OK;
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
