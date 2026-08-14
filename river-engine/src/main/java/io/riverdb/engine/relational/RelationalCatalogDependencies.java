package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.table.IndexedScanCursor;
import io.riverdb.engine.table.IndexedScanResult;
import java.nio.ByteBuffer;

/** Owns bounded catalog dependency scans and foreign-key lookup decisions. */
final class RelationalCatalogDependencies {
  private final RelationalSchemaGate schemaGate;
  private final ByteBuffer catalogScratch = ByteBuffer.allocateDirect(
      CatalogRecord.MAXIMUM_BYTES);
  private final IndexedScanCursor catalogCursor = new IndexedScanCursor();
  private final IndexedScanResult catalogRow = new IndexedScanResult();
  private final TableDefinition referencingTable = new TableDefinition();
  private final TableSchema.ColumnName scannedName = new TableSchema.ColumnName();
  private final ViewDefinition scannedView = new ViewDefinition();

  RelationalCatalogDependencies(RelationalSchemaGate gate) {
    schemaGate = gate;
  }

  StatusCode checkSchemaReferences(
      RelationalSession session, TableDefinition table) {
    StatusCode status = beginScan(session);
    boolean active = status.isOk();
    while (status.isOk()) {
      status = next(session);
      if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
        break;
      }
      if (!status.isOk()) {
        break;
      }
      status = checkSchemaCandidate(table.tableId());
    }
    return closeScan(session, active, status);
  }

  StatusCode checkViewReferences(RelationalSession session, int tableId) {
    StatusCode status = beginScan(session);
    boolean active = status.isOk();
    while (status.isOk()) {
      status = next(session);
      if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
        break;
      }
      if (!status.isOk()) {
        break;
      }
      StatusCode decoded = CatalogViewCodec.decodeForScan(
          catalogRow.row(), catalogScratch, scannedName, scannedView);
      if (decoded == StatusCode.CONFLICT) {
        continue;
      }
      if (!decoded.isOk()) {
        status = decoded;
        break;
      }
      if (scannedView.baseTableId() == tableId) {
        status = StatusCode.CONFLICT;
        break;
      }
    }
    return closeScan(session, active, status);
  }

  private StatusCode checkSchemaCandidate(int tableId) {
    StatusCode decoded = CatalogRecord.decodeTableForScan(
        catalogRow.row(),
        catalogScratch,
        schemaGate,
        scannedName,
        referencingTable);
    if (decoded == StatusCode.CONFLICT) {
      return checkScannedView(tableId);
    }
    if (!decoded.isOk() || !referencingTable.referencesTable(tableId)) {
      return decoded;
    }
    return StatusCode.FOREIGN_KEY_VIOLATION;
  }

  private StatusCode checkScannedView(int tableId) {
    StatusCode status = CatalogViewCodec.decodeForScan(
        catalogRow.row(), catalogScratch, scannedName, scannedView);
    if (status.isOk() && scannedView.baseTableId() == tableId) {
      return StatusCode.CONFLICT;
    }
    return status == StatusCode.CONFLICT ? StatusCode.OK : status;
  }

  private StatusCode beginScan(RelationalSession session) {
    return session.indexedSession().beginScan(Long.MIN_VALUE, 0, catalogCursor);
  }

  private StatusCode next(RelationalSession session) {
    return session.indexedSession().nextScan(catalogCursor, catalogRow);
  }

  private StatusCode closeScan(
      RelationalSession session, boolean active, StatusCode bodyStatus) {
    if (!active) {
      return bodyStatus;
    }
    StatusCode close = session.indexedSession().closeScan(catalogCursor);
    catalogCursor.reset();
    return bodyStatus.isOk() ? close : bodyStatus;
  }
}
