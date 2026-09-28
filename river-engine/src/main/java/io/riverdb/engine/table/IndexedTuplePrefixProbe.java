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
    return probeMatching(
        visible, owner, keyId, schemaId, shape, shape,
        key, offset, length, null, 0, result);
  }

  StatusCode probeMatching(
      long visible, long owner, long keyId, long schemaId,
      TupleShape indexShape, TupleShape prefixShape,
      ByteBuffer key, int offset, int length,
      IndexedTupleIntentJournal intents, long excludedRowId,
      IndexedTupleProbeResult result) {
    result.reset();
    StatusCode status = excludedRowId < 0
        ? StatusCode.INVALID_EXTERNAL_INPUT
        : validate(owner, keyId, schemaId, indexShape, prefixShape,
            key, offset, length);
    if (status.isOk()) status = root.load(visible, keyId, false);
    if (status.isOk()) result.observeCommit(root.observedCommitSequence());
    if (status.isOk() && !root.matches(owner, keyId, schemaId, indexShape)) {
      status = StatusCode.CORRUPTION;
    }
    return status.isOk()
        ? cursor.probe(
            visible,
            root.rootPageId(), kernel.nextPageId(), keyId, schemaId,
            indexShape, prefixShape,
            key, offset, length, intents, excludedRowId, result)
        : status;
  }

  StatusCode probeBuilding(
      long current, long owner, long keyId, long schemaId, long privateOwner,
      TupleShape shape, ByteBuffer key, int offset, int length,
      IndexedTupleProbeResult result) {
    return probeBuildingMatching(
        current, owner, keyId, schemaId, privateOwner,
        shape, shape, key, offset, length, null, 0, result);
  }

  StatusCode probeBuildingMatching(
      long current, long owner, long keyId, long schemaId, long privateOwner,
      TupleShape indexShape, TupleShape prefixShape,
      ByteBuffer key, int offset, int length,
      IndexedTupleIntentJournal intents, long excludedRowId,
      IndexedTupleProbeResult result) {
    result.reset();
    StatusCode status = excludedRowId < 0
        ? StatusCode.INVALID_EXTERNAL_INPUT
        : validate(owner, keyId, schemaId, indexShape, prefixShape,
            key, offset, length);
    if (status.isOk()) status = root.load(current, keyId, false);
    if (status.isOk()) result.observeCommit(root.observedCommitSequence());
    if (status.isOk() && !root.matchesBuilding(
        owner, keyId, schemaId, privateOwner, indexShape)) status = StatusCode.CORRUPTION;
    return status.isOk()
        ? cursor.probe(
            current,
            root.rootPageId(), kernel.nextPageId(), keyId, schemaId,
            indexShape, prefixShape, key, offset, length,
            intents, excludedRowId, result)
        : status;
  }

  private static StatusCode validate(
      long owner, long keyId, long schemaId,
      TupleShape indexShape, TupleShape prefixShape,
      ByteBuffer key, int offset, int length) {
    if (!CatalogKeyspace.validObjectHead(owner) || !CatalogKeyspace.validKeyId(keyId)
        || schemaId <= 0 || indexShape == null || prefixShape == null
        || prefixShape.partCount() <= 0
        || prefixShape.partCount() > indexShape.partCount()
        || indexShape.partCount() > TupleKeyCodec.MAX_INDEX_KEY_PARTS
        || key == null || offset < 0 || length <= 0 || offset > key.limit() - length
        || TupleKeyCodec.isPhysical(key, offset, length)
        || !TupleKeyCodec.matchesShape(key, offset, length, prefixShape)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int part = 0; part < prefixShape.partCount(); part++) {
      if (prefixShape.descriptorAt(part) != indexShape.descriptorAt(part)) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
    }
    return indexShape.maximumEncodedBytes() > TupleKeyCodec.MAX_INDEX_USER_KEY_BYTES
        || indexShape.maximumPhysicalEncodedBytes() > TupleKeyCodec.MAX_PHYSICAL_INDEX_KEY_BYTES
            ? StatusCode.RESOURCE_EXHAUSTED : StatusCode.OK;
  }
}
