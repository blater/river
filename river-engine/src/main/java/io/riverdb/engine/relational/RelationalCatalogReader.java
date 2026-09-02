package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.table.IndexedScanResult;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/** Point resolution and bounded catalog enumeration. */
final class RelationalCatalogReader {
  private final RelationalSchemaGate schemaGate;
  private final IndexedTransactionSession transaction;
  private final RelationalKey.KeyResult key = new RelationalKey.KeyResult();
  private final HeapRowResult row = new HeapRowResult();
  private final ByteBuffer scratch = ByteBuffer.allocateDirect(CatalogRecord.MAXIMUM_BYTES);
  private final RelationalCatalogTableDecoders tableDecoders =
      new RelationalCatalogTableDecoders();
  private final RelationalCatalogObjectReader objects;
  private final RelationalCatalogIndexReader indexes;
  private final CatalogStatisticsReader statistics = new CatalogStatisticsReader();

  RelationalCatalogReader(
      RelationalSchemaGate gate, IndexedTransactionSession indexedTransaction) {
    schemaGate = gate;
    transaction = indexedTransaction;
    objects = new RelationalCatalogObjectReader(gate, indexedTransaction);
    indexes = new RelationalCatalogIndexReader(indexedTransaction);
  }

  StatusCode resolveTable(CharSequence name, TableDefinition result) {
    if (result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode status = RelationalKey.catalogTableKey(name, key);
    if (status.isOk()) {
      status = transaction.fetchByKey(key.space(), key.key(), row);
    }
    if (status.isOk() && CatalogRecord.isDroppingTable(row, scratch)) {
      return StatusCode.CONFLICT;
    }
    return status.isOk()
        ? tableDecoders.named(row, scratch, name, schemaGate, result) : status;
  }

  StatusCode resolveView(CharSequence name, ViewDefinition result) {
    if (result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode status = RelationalKey.catalogTableKey(name, key);
    if (status.isOk()) {
      status = transaction.fetchByKey(key.space(), key.key(), row);
    }
    return status.isOk()
        ? CatalogViewCodec.decode(row, scratch, name, result) : status;
  }

  StatusCode resolveStatistics(
      TableDefinition table, TableStatistics result) {
    if (table == null
        || !table.isOwnedBy(schemaGate) && !table.descriptorView
        || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return statistics.read(transaction, table, result);
  }

  StatusCode beginObjectScan(
      RelationalSession owner, CatalogObjectCursor cursor) {
    return objects.begin(owner, cursor);
  }

  StatusCode nextObject(
      RelationalSession owner,
      CatalogObjectCursor cursor,
      CatalogObjectResult result) {
    return objects.next(owner, cursor, result);
  }

  StatusCode closeObjectScan(
      RelationalSession owner, CatalogObjectCursor cursor) {
    return objects.close(owner, cursor);
  }

  StatusCode beginIndexScan(
      RelationalSession owner,
      CharSequence tableName,
      CatalogIndexCursor cursor) {
    if (cursor == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = resolveTable(tableName, indexes.table());
    return status.isOk()
        ? indexes.begin(
            owner, cursor, indexes.table().tableId(),
            indexes.table().readyIndexCount(), indexes.table().uniqueIndexCount())
        : status;
  }

  StatusCode nextIndex(
      RelationalSession owner,
      CatalogIndexCursor cursor,
      CatalogIndexResult result) {
    return indexes.next(owner, cursor, result);
  }

  StatusCode closeIndexScan(
      RelationalSession owner, CatalogIndexCursor cursor) {
    return indexes.close(owner, cursor);
  }
}
