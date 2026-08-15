package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.table.IndexedScanCursor;
import io.riverdb.engine.table.IndexedScanResult;
import java.nio.ByteBuffer;

/** Owns row-level foreign-key dependency decisions for one serialized session. */
final class RelationalReferentialIntegrity {
  private final RelationalSchemaGate schemaGate;
  private final ByteBuffer catalogScratch = ByteBuffer.allocateDirect(
      CatalogRecord.MAXIMUM_BYTES);
  private final IndexedScanCursor catalogCursor = new IndexedScanCursor();
  private final IndexedScanResult catalogRow = new IndexedScanResult();
  private final TableDefinition referencingTable = new TableDefinition();
  private final TableSchema.ColumnName scannedName = new TableSchema.ColumnName();
  private final RelationalScanCursor referenceCursor = new RelationalScanCursor();
  private final ValueIndexLookupResult reference = new ValueIndexLookupResult();

  RelationalReferentialIntegrity(RelationalSchemaGate gate) {
    schemaGate = gate;
  }

  StatusCode checkDelete(
      RelationalSession session, TableDefinition table, long key) {
    if (session == null
        || table == null
        || !table.isOwnedBy(schemaGate)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = session.indexedSession().beginScan(
        RelationalKey.CATALOG_OBJECT_SPACE,
        Long.MIN_VALUE,
        RelationalKey.CATALOG_SEQUENCE_SPACE,
        Long.MIN_VALUE,
        catalogCursor);
    boolean active = status.isOk();
    while (status.isOk()) {
      status = session.indexedSession().nextScan(catalogCursor, catalogRow);
      if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
        break;
      }
      if (!status.isOk()) {
        break;
      }
      status = checkCandidate(session, table.tableId(), key);
    }
    if (active) {
      StatusCode close = session.indexedSession().closeScan(catalogCursor);
      catalogCursor.reset();
      if (status.isOk()) {
        status = close;
      }
    }
    return status;
  }

  private StatusCode checkCandidate(
      RelationalSession session, int tableId, long key) {
    StatusCode status = CatalogRecord.decodeTableForScan(
        catalogRow.row(),
        catalogScratch,
        schemaGate,
        scannedName,
        referencingTable);
    if (status == StatusCode.CONFLICT
        || status.isOk() && !referencingTable.referencesTable(tableId)) {
      return StatusCode.OK;
    }
    if (!status.isOk()) {
      return status;
    }
    for (int column = 1; column < referencingTable.columnCount(); column++) {
      if (referencingTable.hasReference(column)
          && referencingTable.referenceTableId(column) == tableId) {
        status = referenceExists(session, column, key);
        if (!status.isOk()) {
          return status;
        }
      }
    }
    return StatusCode.OK;
  }

  private StatusCode referenceExists(
      RelationalSession session, int column, long key) {
    if (!referencingTable.hasIndexOn(column)) {
      return StatusCode.CORRUPTION;
    }
    StatusCode status;
    if (referencingTable.hasUniqueIndexOn(column)) {
      status = session.fetchByUniqueValue(
          referencingTable, column, key, reference);
    } else {
      status = session.beginNonUniqueValueLookup(
          referencingTable, column, key, referenceCursor);
      if (status.isOk()) {
        status = session.nextNonUniqueValueLookup(
            referencingTable, referenceCursor, reference);
        StatusCode close = session.closeScan(referenceCursor);
        referenceCursor.reset();
        if (status.isOk() && !close.isOk()) {
          status = close;
        }
      }
    }
    if (status.isOk()) {
      return StatusCode.FOREIGN_KEY_VIOLATION;
    }
    return status == StatusCode.CONFLICT
            || status == StatusCode.INVALID_EXTERNAL_INPUT
        ? StatusCode.OK : status;
  }
}
