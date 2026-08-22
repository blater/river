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
  private final IndexedScanResult scanRow = new IndexedScanResult();
  private final ByteBuffer scratch = ByteBuffer.allocateDirect(CatalogRecord.MAXIMUM_BYTES);
  private final ViewDefinition scannedView = new ViewDefinition();
  private final TableDefinition scannedTable = new TableDefinition();
  private final TableDefinition scannedIndexTable = new TableDefinition();
  private final CatalogIndexCodec.Result scannedIndex = new CatalogIndexCodec.Result();
  private final TableSchema.ColumnName objectName = new TableSchema.ColumnName();
  private final TableSchema.ColumnName indexName = new TableSchema.ColumnName();

  RelationalCatalogReader(
      RelationalSchemaGate gate, IndexedTransactionSession indexedTransaction) {
    schemaGate = gate;
    transaction = indexedTransaction;
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
        ? CatalogRecord.decodeTable(row, scratch, name, schemaGate, result) : status;
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
    if (table == null || !table.isOwnedBy(schemaGate) || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode status = transaction.fetchByKey(
        RelationalKey.CATALOG_SEQUENCE_SPACE,
        RelationalKey.tableStatisticsKey(table.tableId()),
        row);
    return status.isOk()
        ? CatalogStatisticsCodec.decode(row, scratch, table, result) : status;
  }

  StatusCode beginObjectScan(
      RelationalSession owner, CatalogObjectCursor cursor) {
    if (cursor == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = cursor.reset();
    if (status.isOk()) {
      status = transaction.beginScan(
          RelationalKey.CATALOG_OBJECT_SPACE,
          Long.MIN_VALUE,
          RelationalKey.CATALOG_SEQUENCE_SPACE,
          Long.MIN_VALUE,
          cursor.indexed());
    }
    return status.isOk() ? cursor.claim(owner) : status;
  }

  StatusCode nextObject(
      RelationalSession owner,
      CatalogObjectCursor cursor,
      CatalogObjectResult result) {
    if (cursor == null || result == null || !cursor.isOwnedBy(owner)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    while (true) {
      StatusCode status = transaction.nextScan(cursor.indexed(), scanRow);
      if (status == StatusCode.CONFLICT) {
        return StatusCode.OK;
      }
      if (!status.isOk()) {
        return status;
      }
      if (CatalogRecord.isDroppingTable(scanRow.row(), scratch)) {
        continue;
      }
      status = decodeObject(scanRow.row(), result);
      if (status != StatusCode.CONFLICT) {
        return status;
      }
    }
  }

  StatusCode closeObjectScan(
      RelationalSession owner, CatalogObjectCursor cursor) {
    if (cursor == null || !cursor.isOwnedBy(owner)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = transaction.closeScan(cursor.indexed());
    if (status.isOk()) {
      cursor.complete();
    }
    return status;
  }

  StatusCode beginIndexScan(
      RelationalSession owner,
      CharSequence tableName,
      CatalogIndexCursor cursor) {
    if (cursor == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = resolveTable(tableName, scannedIndexTable);
    if (status.isOk()) {
      status = cursor.reset();
    }
    if (status.isOk()) {
      status = transaction.beginScan(
          RelationalKey.CATALOG_OBJECT_SPACE,
          Long.MIN_VALUE,
          RelationalKey.CATALOG_SEQUENCE_SPACE,
          Long.MIN_VALUE,
          cursor.indexed());
    }
    return status.isOk()
        ? cursor.claim(
            owner,
            scannedIndexTable.tableId(),
            scannedIndexTable.readyIndexCount())
        : status;
  }

  StatusCode nextIndex(
      RelationalSession owner,
      CatalogIndexCursor cursor,
      CatalogIndexResult result) {
    if (cursor == null || result == null || !cursor.isOwnedBy(owner)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    if (cursor.takePrimary()) {
      result.setPrimary(scannedIndexTable.columnName(0));
      return StatusCode.OK;
    }
    while (true) {
      StatusCode status = transaction.nextScan(cursor.indexed(), scanRow);
      if (status == StatusCode.CONFLICT) {
        return cursor.allSecondariesObserved()
            ? StatusCode.OK : StatusCode.CORRUPTION;
      }
      if (!status.isOk()) {
        return status;
      }
      indexName.reset();
      status = CatalogIndexCodec.decodeForTable(
          scanRow.row(), scratch, cursor.tableId(), indexName, scannedIndex);
      if (status == StatusCode.CONFLICT
          || status.isOk() && scannedIndex.state() != TableDefinition.INDEX_READY) {
        continue;
      }
      return status.isOk() ? publishIndex(cursor, result) : status;
    }
  }

  StatusCode closeIndexScan(
      RelationalSession owner, CatalogIndexCursor cursor) {
    if (cursor == null || !cursor.isOwnedBy(owner)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = transaction.closeScan(cursor.indexed());
    if (status.isOk()) {
      cursor.complete();
      scannedIndexTable.reset();
    }
    return status;
  }

  private StatusCode decodeObject(
      HeapRowResult source, CatalogObjectResult result) {
    StatusCode status = CatalogRecord.decodeTableForScan(
        source, scratch, schemaGate, objectName, scannedTable);
    if (status.isOk()) {
      result.set(objectName, CatalogObjectResult.TABLE);
      return StatusCode.OK;
    }
    if (status != StatusCode.CONFLICT) {
      return status;
    }
    status = CatalogViewCodec.decodeForScan(
        source, scratch, objectName, scannedView);
    if (status.isOk()) {
      result.set(objectName, CatalogObjectResult.VIEW);
    }
    return status;
  }

  private StatusCode publishIndex(
      CatalogIndexCursor cursor, CatalogIndexResult result) {
    int slot = scannedIndexTable.readyIndexSlotForTableId(
        scannedIndex.indexTableId());
    if (slot < 0
        || !cursor.recordSecondary(slot)
        || scannedIndexTable.indexIsUnique(slot) != scannedIndex.isUnique()
        || scannedIndexTable.indexIsConstraint(slot) != scannedIndex.isConstraint()) {
      return StatusCode.CORRUPTION;
    }
    result.set(
        indexName,
        scannedIndexTable.columnName(scannedIndexTable.uniqueIndexColumn(slot)),
        scannedIndex.isUnique(),
        scannedIndex.isConstraint());
    return StatusCode.OK;
  }
}
