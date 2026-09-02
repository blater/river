package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.table.IndexedScanResult;
import io.riverdb.engine.table.IndexedTransactionSession;
import java.nio.ByteBuffer;

/** Legacy single-column index catalog enumeration. */
final class RelationalCatalogIndexReader {
  private final IndexedTransactionSession transaction;
  private final IndexedScanResult scanRow = new IndexedScanResult();
  private final ByteBuffer scratch = ByteBuffer.allocateDirect(CatalogRecord.MAXIMUM_BYTES);
  private final TableDefinition table = new TableDefinition();
  private final CatalogIndexCodec.Result scanned = new CatalogIndexCodec.Result();
  private final TableSchema.ColumnName indexName = new TableSchema.ColumnName();

  RelationalCatalogIndexReader(IndexedTransactionSession indexedTransaction) {
    transaction = indexedTransaction;
  }

  TableDefinition table() { return table; }

  StatusCode begin(
      RelationalSession owner, CatalogIndexCursor cursor, int tableId,
      int readyIndexes, int uniqueIndexes) {
    StatusCode status = cursor.reset();
    if (status.isOk()) status = transaction.beginScan(
        RelationalKey.CATALOG_OBJECT_SPACE, Long.MIN_VALUE,
        RelationalKey.CATALOG_SEQUENCE_SPACE, Long.MIN_VALUE, cursor.indexed());
    return status.isOk()
        ? cursor.claim(owner, tableId, readyIndexes, uniqueIndexes) : status;
  }

  StatusCode next(
      RelationalSession owner, CatalogIndexCursor cursor, CatalogIndexResult result) {
    if (cursor == null || result == null || !cursor.isOwnedBy(owner)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    if (cursor.takePrimary()) {
      result.setPrimary(table.columnName(0));
      return StatusCode.OK;
    }
    while (true) {
      StatusCode status = transaction.nextScan(cursor.indexed(), scanRow);
      if (status == StatusCode.CONFLICT) {
        return cursor.allSecondariesObserved() ? StatusCode.OK : StatusCode.CORRUPTION;
      }
      if (!status.isOk()) return status;
      indexName.reset();
      status = CatalogIndexCodec.decodeForTable(
          scanRow.row(), scratch, cursor.tableId(), indexName, scanned);
      if (status == StatusCode.CONFLICT
          || status.isOk() && scanned.state() != TableDefinition.INDEX_READY) continue;
      return status.isOk() ? publish(cursor, result) : status;
    }
  }

  StatusCode close(RelationalSession owner, CatalogIndexCursor cursor) {
    if (cursor == null || !cursor.isOwnedBy(owner)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = transaction.closeScan(cursor.indexed());
    if (status.isOk()) {
      cursor.complete();
      table.reset();
    }
    return status;
  }

  private StatusCode publish(CatalogIndexCursor cursor, CatalogIndexResult result) {
    int slot = table.readyIndexSlotForTableId(scanned.indexTableId());
    if (slot < 0 || !cursor.recordSecondary(slot)
        || table.indexIsUnique(slot) != scanned.isUnique()
        || table.indexIsConstraint(slot) != scanned.isConstraint()) {
      return StatusCode.CORRUPTION;
    }
    result.set(
        indexName,
        table.columnName(table.uniqueIndexColumn(slot)),
        scanned.isUnique(),
        scanned.isConstraint());
    return StatusCode.OK;
  }
}
