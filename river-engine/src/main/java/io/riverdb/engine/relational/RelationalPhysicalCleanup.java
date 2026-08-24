package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.table.IndexedScanCursor;
import io.riverdb.engine.table.IndexedScanResult;
import io.riverdb.tx.api.TransactionOutcome;
import io.riverdb.tx.api.TransactionState;
import java.nio.ByteBuffer;

/** Bounded deletion of physical table rows and their catalog records. */
final class RelationalPhysicalCleanup {
  private static final int BATCH_ROWS = 48;

  private final RelationalSchemaGate schemaGate;
  final IndexedScanCursor scanCursor = new IndexedScanCursor();
  final IndexedScanResult scanRow = new IndexedScanResult();
  final int[] rowSpaces = new int[BATCH_ROWS];
  final long[] rowKeys = new long[BATCH_ROWS];
  final long[] indexCatalogKeys =
      new long[TableDefinition.MAXIMUM_INDEXES];
  final IndexedScanCursor catalogCursor = new IndexedScanCursor();
  final IndexedScanResult catalogRow = new IndexedScanResult();
  final ByteBuffer catalogScratch =
      ByteBuffer.allocateDirect(CatalogRecord.MAXIMUM_BYTES);
  final CatalogIndexCodec.Result indexRecord = new CatalogIndexCodec.Result();
  final RelationalKey.KeyResult catalogKey = new RelationalKey.KeyResult();
  StatusCode collectStatus;
  boolean scanExhausted;
  boolean batchComplete;

  RelationalPhysicalCleanup(RelationalSchemaGate gate) {
    schemaGate = gate;
  }

  StatusCode cleanupDroppingTable(
      RelationalSession session,
      TableDefinition table,
      CharSequence tableName,
      TransactionOutcome outcome,
      int maximumBatches) {
    StatusCode status = cleanupPhysicalTables(
        session, table, outcome, maximumBatches);
    if (status.isOk() && !batchComplete) {
      session.releasePersistentSchemaChange();
      return StatusCode.RETRY;
    }
    if (status.isOk()) {
      status = removeCatalog(session, table, tableName, outcome);
    }
    if (!status.isOk()) {
      session.releasePersistentSchemaChange();
    }
    return status;
  }

  private StatusCode cleanupPhysicalTables(
      RelationalSession session,
      TableDefinition table,
      TransactionOutcome outcome,
      int maximumBatches) {
    StatusCode status = StatusCode.OK;
    int batches = 0;
    for (int slot = 0;
        status.isOk() && slot < table.uniqueIndexCount();
        slot++) {
      batchComplete = false;
      while (status.isOk() && !batchComplete && batches < maximumBatches) {
        status = cleanupBatch(
            session, table.uniqueIndexTableId(slot), outcome);
        batches++;
      }
      if (status.isOk() && !batchComplete) {
        return status;
      }
    }
    batchComplete = false;
    while (status.isOk() && !batchComplete && batches < maximumBatches) {
      status = cleanupBatch(session, table.tableId(), outcome);
      batches++;
    }
    return status;
  }

  private StatusCode cleanupBatch(
      RelationalSession session,
      int tableId,
      TransactionOutcome outcome) {
    return RelationalPhysicalBatchCleanup.run(this, session, tableId, outcome);
  }

  private StatusCode removeCatalog(
      RelationalSession session,
      TableDefinition table,
      CharSequence tableName,
      TransactionOutcome outcome) {
    return RelationalCatalogCleanup.remove(this, session, table, tableName, outcome);
  }

  StatusCode deleteStatistics(RelationalSession session, int tableId) {
    long key = RelationalKey.tableStatisticsKey(tableId);
    StatusCode status = session.indexedSession().fetchByKey(
        RelationalKey.CATALOG_SEQUENCE_SPACE, key, catalogRow.row());
    if (status == StatusCode.CONFLICT) return StatusCode.OK;
    return status.isOk()
        ? session.indexedSession().delete(RelationalKey.CATALOG_SEQUENCE_SPACE, key)
        : status;
  }

  int collectIndexCatalogKeys(
      RelationalSession session, TableDefinition table) {
    int count = 0;
    collectStatus = StatusCode.OK;
    while (collectStatus.isOk()) {
      collectStatus = session.indexedSession().nextScan(catalogCursor, catalogRow);
      if (collectStatus == StatusCode.CONFLICT) {
        collectStatus = StatusCode.OK;
        break;
      }
      if (!collectStatus.isOk()) {
        break;
      }
      StatusCode decoded = CatalogIndexCodec.decodeForTable(
          catalogRow.row(), catalogScratch, table.tableId(), indexRecord);
      if (decoded == StatusCode.CONFLICT) {
        continue;
      }
      if (!decoded.isOk() || count >= indexCatalogKeys.length) {
        collectStatus = decoded.isOk() ? StatusCode.CORRUPTION : decoded;
      } else {
        indexCatalogKeys[count++] = catalogRow.key();
      }
    }
    return count;
  }

  static StatusCode finishTransaction(
      RelationalSession session,
      TransactionOutcome outcome,
      StatusCode bodyStatus) {
    if (bodyStatus.isOk()) {
      return session.commit(outcome);
    }
    if (session.indexedSession().transaction().state() != TransactionState.ACTIVE) {
      return bodyStatus;
    }
    StatusCode abort = session.abort(outcome);
    return abort.isOk() ? bodyStatus : abort;
  }
}
