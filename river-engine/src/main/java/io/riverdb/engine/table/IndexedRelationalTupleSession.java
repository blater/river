package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.format.btree.TupleKeyCodec;
import io.riverdb.format.btree.TupleBTreePageCodec;
import io.riverdb.format.btree.TupleRowOverflowCodec;
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
  private int overflowPageId;
  private long overflowGeneration;

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

  StatusCode insertValue(
      ByteBuffer key, ByteBuffer value, int valueOffset, int valueLength,
      int overflowPageId, long overflowGeneration, long modificationSequence) {
    StatusCode status = provider.beginVariableAllocation();
    if (!status.isOk()) return status;
    status = prepareValue(
        key, value, valueOffset, valueLength, overflowPageId, overflowGeneration);
    if (!status.isOk()) return finish(status);
    status = tree.insert(key, key.position(), key.remaining(),
        this.overflowPageId == 0 ? value : null,
        this.overflowPageId == 0 ? valueOffset : 0, valueLength,
        this.overflowPageId, this.overflowGeneration, modificationSequence, workspace);
    return finish(status);
  }

  StatusCode replaceValue(
      ByteBuffer key, ByteBuffer value, int valueOffset, int valueLength,
      int overflowPageId, long overflowGeneration, long modificationSequence) {
    StatusCode status = provider.beginVariableAllocation();
    if (!status.isOk()) return status;
    status = prepareValue(
        key, value, valueOffset, valueLength, overflowPageId, overflowGeneration);
    if (!status.isOk()) return finish(status);
    status = tree.replaceValue(key, key.position(), key.remaining(),
        this.overflowPageId == 0 ? value : null,
        this.overflowPageId == 0 ? valueOffset : 0, valueLength,
        this.overflowPageId, this.overflowGeneration, modificationSequence, workspace);
    int removedPageId = workspace.removedOverflowPageId();
    long removedGeneration = workspace.removedOverflowGeneration();
    if (status.isOk() && removedPageId != 0
        && (removedPageId != this.overflowPageId
            || removedGeneration != this.overflowGeneration)) {
      status = provider.retireOverflow(
          removedPageId, removedGeneration,
          TupleKeyCodec.logicalRowId(key, key.position(), key.remaining()),
          modificationSequence);
    }
    return finish(status);
  }

  private StatusCode prepareValue(
      ByteBuffer key, ByteBuffer value, int valueOffset, int valueLength,
      int suppliedOverflowPageId, long suppliedOverflowGeneration) {
    overflowPageId = suppliedOverflowPageId;
    overflowGeneration = suppliedOverflowGeneration;
    if (suppliedOverflowPageId != 0 || TupleBTreePageCodec.inlineEligible(
        key.remaining(), valueLength)) return StatusCode.OK;
    StatusCode status = provider.allocateOverflow();
    if (!status.isOk()) return status;
    IndexedOperationPage page = provider.overflowPage();
    overflowPageId = page.pageId();
    overflowGeneration = page.durableGeneration();
    status = TupleRowOverflowCodec.encode(
        page.payload(), 0,
        TupleKeyCodec.logicalRowId(key, key.position(), key.remaining()),
        value, valueOffset, valueLength);
    StatusCode released = provider.releaseOverflow();
    return status.isOk() ? released : status;
  }

  StatusCode delete(ByteBuffer key, long modificationSequence) {
    StatusCode status = provider.begin(0);
    if (!status.isOk()) return status;
    status = tree.delete(key, key.position(), key.remaining(), workspace);
    if (status.isOk() && workspace.removedOverflowPageId() != 0) {
      status = provider.retireOverflow(
        workspace.removedOverflowPageId(), workspace.removedOverflowGeneration(),
        TupleKeyCodec.logicalRowId(key, key.position(), key.remaining()),
        modificationSequence);
    }
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
