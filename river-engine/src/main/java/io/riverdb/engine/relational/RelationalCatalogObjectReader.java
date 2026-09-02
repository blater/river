package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.table.IndexedScanResult;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/** Sequential legacy/catalog-v2 object namespace scan. */
final class RelationalCatalogObjectReader {
  private final RelationalSchemaGate schemaGate;
  private final IndexedTransactionSession transaction;
  private final IndexedScanResult scanRow = new IndexedScanResult();
  private final ByteBuffer scratch = ByteBuffer.allocateDirect(CatalogRecord.MAXIMUM_BYTES);
  private final RelationalCatalogTableDecoders tableDecoders =
      new RelationalCatalogTableDecoders();
  private final RelationalDescriptorNameRow descriptorName =
      new RelationalDescriptorNameRow();
  private final ViewDefinition scannedView = new ViewDefinition();
  private final TableDefinition scannedTable = new TableDefinition();
  private final TableSchema.ColumnName objectName = new TableSchema.ColumnName();

  RelationalCatalogObjectReader(
      RelationalSchemaGate gate, IndexedTransactionSession indexedTransaction) {
    schemaGate = gate;
    transaction = indexedTransaction;
  }

  StatusCode begin(RelationalSession owner, CatalogObjectCursor cursor) {
    if (cursor == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = cursor.reset();
    if (status.isOk()) status = transaction.beginScan(
        RelationalKey.CATALOG_OBJECT_SPACE, Long.MIN_VALUE,
        RelationalKey.CATALOG_SEQUENCE_SPACE, Long.MIN_VALUE, cursor.indexed());
    return status.isOk() ? cursor.claim(owner) : status;
  }

  StatusCode next(
      RelationalSession owner, CatalogObjectCursor cursor, CatalogObjectResult result) {
    if (cursor == null || result == null || !cursor.isOwnedBy(owner)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    while (true) {
      StatusCode status = transaction.nextScan(cursor.indexed(), scanRow);
      if (status == StatusCode.CONFLICT) {
        if (cursor.descriptorPhase()) return StatusCode.OK;
        status = beginDescriptors(cursor);
        if (!status.isOk()) return status;
        continue;
      }
      if (!status.isOk()) return status;
      if (cursor.descriptorPhase()) return publishDescriptor(result);
      if (CatalogRecord.isDroppingTable(scanRow.row(), scratch)) continue;
      status = decodeLegacy(scanRow.row(), result);
      if (status != StatusCode.CONFLICT) return status;
    }
  }

  StatusCode close(RelationalSession owner, CatalogObjectCursor cursor) {
    if (cursor == null || !cursor.isOwnedBy(owner)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = transaction.closeScan(cursor.indexed());
    if (status.isOk()) cursor.complete();
    return status;
  }

  private StatusCode beginDescriptors(CatalogObjectCursor cursor) {
    StatusCode status = transaction.closeScan(cursor.indexed());
    if (status.isOk()) status = cursor.indexed().reset();
    if (status.isOk()) status = transaction.beginScan(
        RelationalDescriptorKeyspace.NAME_MAP_SPACE, Long.MIN_VALUE,
        RelationalDescriptorKeyspace.NAME_MAP_SPACE + 1, Long.MIN_VALUE,
        cursor.indexed());
    if (status.isOk()) cursor.beginDescriptorPhase();
    else cursor.complete();
    return status;
  }

  private StatusCode publishDescriptor(CatalogObjectResult result) {
    StatusCode status = descriptorName.read(scanRow);
    if (status.isOk()) result.set(descriptorName, CatalogObjectResult.TABLE);
    return status;
  }

  private StatusCode decodeLegacy(
      HeapRowResult source, CatalogObjectResult result) {
    StatusCode status = tableDecoders.scanned(
        source, scratch, schemaGate, objectName, scannedTable);
    if (status.isOk()) {
      result.set(objectName, CatalogObjectResult.TABLE);
      return StatusCode.OK;
    }
    if (status != StatusCode.CONFLICT) return status;
    status = CatalogViewCodec.decodeForScan(
        source, scratch, objectName, scannedView);
    if (status.isOk()) result.set(objectName, CatalogObjectResult.VIEW);
    return status;
  }
}
