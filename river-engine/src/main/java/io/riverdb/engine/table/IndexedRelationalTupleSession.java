package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.format.btree.TupleKeyCodec;
import io.riverdb.format.page.PageCodec;
import io.riverdb.storage.btree.TupleBTree;
import io.riverdb.storage.btree.TupleBTreeInsertPreflightResult;
import io.riverdb.storage.btree.BTreeStructuralLimits;
import io.riverdb.storage.btree.TupleBTreeTreeWorkspace;
import io.riverdb.storage.btree.TupleBTreeValidationResult;
import java.nio.ByteBuffer;

/** Reusable operation owner for one tuple root and descriptor. */
final class IndexedRelationalTupleSession {
  private final IndexedTupleRootState root;
  private final IndexedTuplePageProvider provider;
  private final TupleBTree tree;
  private final TupleBTreeTreeWorkspace workspace;
  private final TupleBTreeInsertPreflightResult preflight =
      new TupleBTreeInsertPreflightResult();
  private final TupleBTreeValidationResult validation = new TupleBTreeValidationResult();

  IndexedRelationalTupleSession(IndexedPageSet pages) {
    root = new IndexedTupleRootState(1, 1, 0);
    provider = new IndexedTuplePageProvider(pages, root);
    tree = new TupleBTree(provider, 1, null);
    int height = BTreeStructuralLimits.MAXIMUM_LEVELS;
    workspace = new TupleBTreeTreeWorkspace(
        ByteBuffer.allocate(PageCodec.MAX_PAYLOAD_BYTES),
        ByteBuffer.allocate(TupleKeyCodec.MAX_PHYSICAL_INDEX_KEY_BYTES),
        new int[height], new int[height], new int[height]);
  }

  StatusCode configure(long keyId, long schemaId, int rootPageId, TupleShape shape) {
    preflight.reset();
    validation.reset();
    if (!provider.reusable()) return StatusCode.INVARIANT_BROKEN;
    if (!root.canConfigure(keyId, schemaId, rootPageId)
        || shape == null || shape.partCount() <= 0
        || shape.partCount() > SqlShapeLimits.MAX_KEY_PARTS) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (shape.maximumEncodedBytes() > TupleKeyCodec.MAX_INDEX_USER_KEY_BYTES
        || shape.maximumPhysicalEncodedBytes() <= 0
        || shape.maximumPhysicalEncodedBytes() > TupleKeyCodec.MAX_PHYSICAL_INDEX_KEY_BYTES) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = root.configure(keyId, schemaId, rootPageId);
    if (status.isOk()) status = tree.configure(provider, schemaId, shape);
    return status;
  }

  StatusCode initialize() {
    StatusCode status = provider.begin(1);
    if (!status.isOk()) return status;
    status = tree.initialize(workspace);
    return finish(status);
  }

  StatusCode insert(ByteBuffer key) {
    StatusCode status = provider.begin(0);
    if (!status.isOk()) return status;
    status = tree.preflightInsert(
        key, key.position(), key.remaining(), workspace, preflight);
    status = finish(status);
    if (!status.isOk() || preflight.keyExists()) {
      return status.isOk() ? StatusCode.CORRUPTION : status;
    }
    status = provider.begin(preflight.newPageCount());
    if (!status.isOk()) return status;
    status = tree.insert(key, key.position(), key.remaining(), workspace);
    StatusCode finished = finish(status);
    return finished;
  }

  StatusCode delete(ByteBuffer key) {
    StatusCode status = provider.begin(0);
    if (!status.isOk()) return status;
    status = tree.delete(key, key.position(), key.remaining(), workspace);
    return finish(status);
  }

  StatusCode validate() {
    StatusCode status = provider.begin(0);
    if (!status.isOk()) return status;
    status = tree.validate(workspace, validation);
    status = finish(status);
    return status.isOk() && validation.pageCount() == provider.ownedPageCount()
        ? StatusCode.OK : status.isOk() ? StatusCode.CORRUPTION : status;
  }

  private StatusCode finish(StatusCode status) {
    StatusCode finished = provider.finish(status);
    StatusCode published = finished.isOk() ? provider.publishRoot() : finished;
    return published;
  }

  int rootPageId() { return root.rootPageId(); }
}
