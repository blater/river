package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.format.btree.TupleKeyCodec;
import io.riverdb.format.catalog.CatalogKeyspace;
import java.nio.ByteBuffer;

/** Probes one validated READY tuple root while the table monitor excludes publication. */
final class IndexedTuplePrefixProbe {
  private final IndexedTableKernel kernel;
  private final IndexedTupleRootSnapshot root;
  private final IndexedTuplePrefixCursor cursor;

  IndexedTuplePrefixProbe(IndexedTableKernel table, IndexedPageSet pages) {
    kernel = table;
    root = new IndexedTupleRootSnapshot(table);
    cursor = new IndexedTuplePrefixCursor(pages);
  }

  StatusCode probe(
      long visible, long owner, long keyId, long schemaId, TupleShape shape,
      ByteBuffer key, int offset, int length, IndexedTupleProbeResult result) {
    return probeAfter(
        visible, owner, keyId, schemaId, shape,
        key, offset, length, 0, result);
  }

  StatusCode probeAfter(
      long visible, long owner, long keyId, long schemaId, TupleShape shape,
      ByteBuffer key, int offset, int length, long afterLogicalRowId,
      IndexedTupleProbeResult result) {
    result.reset();
    StatusCode status = afterLogicalRowId < 0
        ? StatusCode.INVALID_EXTERNAL_INPUT
        : validate(owner, keyId, schemaId, shape, key, offset, length);
    if (status.isOk()) status = root.load(visible, keyId);
    if (status.isOk()) result.observeCommit(root.observedCommitSequence());
    if (status.isOk() && !root.matches(owner, keyId, schemaId, shape)) {
      status = StatusCode.CORRUPTION;
    }
    return status.isOk()
        ? cursor.probe(
            visible,
            root.rootPageId(), kernel.nextPageId(), keyId, schemaId, shape,
            key, offset, length, afterLogicalRowId, result)
        : status;
  }

  StatusCode probeBuilding(
      long current, long owner, long keyId, long schemaId, long privateOwner,
      TupleShape shape, ByteBuffer key, int offset, int length,
      IndexedTupleProbeResult result) {
    return probeBuildingAfter(
        current, owner, keyId, schemaId, privateOwner,
        shape, key, offset, length, 0, result);
  }

  StatusCode probeBuildingAfter(
      long current, long owner, long keyId, long schemaId, long privateOwner,
      TupleShape shape, ByteBuffer key, int offset, int length,
      long afterLogicalRowId, IndexedTupleProbeResult result) {
    result.reset();
    StatusCode status = afterLogicalRowId < 0
        ? StatusCode.INVALID_EXTERNAL_INPUT
        : validate(owner, keyId, schemaId, shape, key, offset, length);
    if (status.isOk()) status = root.load(current, keyId);
    if (status.isOk()) result.observeCommit(root.observedCommitSequence());
    if (status.isOk() && !root.matchesBuilding(
        owner, keyId, schemaId, privateOwner, shape)) status = StatusCode.CORRUPTION;
    return status.isOk()
        ? cursor.probe(
            current,
            root.rootPageId(), kernel.nextPageId(), keyId, schemaId,
            shape, key, offset, length, afterLogicalRowId, result)
        : status;
  }

  private static StatusCode validate(
      long owner, long keyId, long schemaId, TupleShape shape,
      ByteBuffer key, int offset, int length) {
    if (!CatalogKeyspace.validObjectHead(owner) || !CatalogKeyspace.validKeyId(keyId)
        || schemaId <= 0 || shape == null || shape.partCount() <= 0
        || shape.partCount() > TupleKeyCodec.MAX_INDEX_KEY_PARTS
        || key == null || offset < 0 || length <= 0 || offset > key.limit() - length
        || TupleKeyCodec.isPhysical(key, offset, length)
        || !TupleKeyCodec.matchesShape(key, offset, length, shape)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return shape.maximumEncodedBytes() > TupleKeyCodec.MAX_INDEX_USER_KEY_BYTES
        || shape.maximumPhysicalEncodedBytes() > TupleKeyCodec.MAX_PHYSICAL_INDEX_KEY_BYTES
            ? StatusCode.RESOURCE_EXHAUSTED : StatusCode.OK;
  }
}
