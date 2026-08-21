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
  private final IndexedScanCursor scanCursor = new IndexedScanCursor();
  private final IndexedScanResult scanRow = new IndexedScanResult();
  private final int[] rowSpaces = new int[BATCH_ROWS];
  private final long[] rowKeys = new long[BATCH_ROWS];
  private final long[] indexCatalogKeys =
      new long[TableDefinition.MAXIMUM_INDEXES];
  private final IndexedScanCursor catalogCursor = new IndexedScanCursor();
  private final IndexedScanResult catalogRow = new IndexedScanResult();
  private final ByteBuffer catalogScratch =
      ByteBuffer.allocateDirect(CatalogRecord.MAXIMUM_BYTES);
  private final CatalogIndexCodec.Result indexRecord = new CatalogIndexCodec.Result();
  private final RelationalKey.KeyResult catalogKey = new RelationalKey.KeyResult();
  private StatusCode collectStatus;
  private boolean scanExhausted;
  private boolean batchComplete;

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
    batchComplete = false;
    StatusCode status = session.begin(io.riverdb.tx.api.IsolationLevel.REPEATABLE_READ);
    if (status.isOk()) {
      int dataSpace = RelationalKey.dataSpace(tableId);
      status = session.indexedSession().beginScan(
          dataSpace,
          Long.MIN_VALUE,
          RelationalKey.auxiliarySpace(tableId) + 1,
          Long.MIN_VALUE,
          scanCursor);
    }
    int count = 0;
    if (status.isOk()) {
      count = collectKeys(session);
      status = collectStatus;
    }
    if (scanCursor.isActive()) {
      StatusCode close = session.indexedSession().closeScan(scanCursor);
      if (status.isOk()) {
        status = close;
      }
    }
    scanCursor.reset();
    for (int index = 0; status.isOk() && index < count; index++) {
      status = session.indexedSession().delete(rowSpaces[index], rowKeys[index]);
      rowSpaces[index] = 0;
      rowKeys[index] = 0;
    }
    if (status.isOk()) {
      status = session.commitBuildPhase(outcome);
    } else if (session.indexedSession().transaction().state() == TransactionState.ACTIVE) {
      StatusCode abort = session.abortBuildPhase(outcome);
      if (!abort.isOk()) {
        status = abort;
      }
    }
    batchComplete = status.isOk() && scanExhausted;
    return status;
  }

  private int collectKeys(RelationalSession session) {
    int count = 0;
    collectStatus = StatusCode.OK;
    scanExhausted = false;
    while (collectStatus.isOk() && count < rowKeys.length) {
      collectStatus = session.indexedSession().nextScan(scanCursor, scanRow);
      if (collectStatus == StatusCode.CONFLICT) {
        collectStatus = StatusCode.OK;
        scanExhausted = true;
        break;
      }
      if (collectStatus.isOk()) {
        rowSpaces[count] = scanRow.keySpace();
        rowKeys[count++] = scanRow.key();
      }
    }
    return count;
  }

  private StatusCode removeCatalog(
      RelationalSession session,
      TableDefinition table,
      CharSequence tableName,
      TransactionOutcome outcome) {
    StatusCode status = session.begin(io.riverdb.tx.api.IsolationLevel.SERIALIZABLE);
    boolean scanActive = false;
    if (status.isOk()) {
      status = session.indexedSession().beginScan(
          RelationalKey.CATALOG_OBJECT_SPACE,
          Long.MIN_VALUE,
          RelationalKey.CATALOG_SEQUENCE_SPACE,
          Long.MIN_VALUE,
          catalogCursor);
      scanActive = status.isOk();
    }
    int count = status.isOk() ? collectIndexCatalogKeys(session, table) : 0;
    if (status.isOk()) {
      status = collectStatus;
    }
    if (scanActive) {
      StatusCode close = session.indexedSession().closeScan(catalogCursor);
      if (status.isOk()) {
        status = close;
      }
    }
    catalogCursor.reset();
    for (int index = 0; status.isOk() && index < count; index++) {
      status = session.indexedSession().delete(
          RelationalKey.CATALOG_OBJECT_SPACE, indexCatalogKeys[index]);
      indexCatalogKeys[index] = 0;
    }
    if (status.isOk()) {
      status = RelationalKey.catalogTableKey(tableName, catalogKey);
    }
    if (status.isOk()) {
      status = session.indexedSession().delete(catalogKey.space(), catalogKey.key());
    }
    if (status.isOk()) status = deleteStatistics(session, table.tableId());
    return finishTransaction(session, outcome, status);
  }

  private StatusCode deleteStatistics(RelationalSession session, int tableId) {
    long key = RelationalKey.tableStatisticsKey(tableId);
    StatusCode status = session.indexedSession().fetchByKey(
        RelationalKey.CATALOG_SEQUENCE_SPACE, key, catalogRow.row());
    if (status == StatusCode.CONFLICT) return StatusCode.OK;
    return status.isOk()
        ? session.indexedSession().delete(RelationalKey.CATALOG_SEQUENCE_SPACE, key)
        : status;
  }

  private int collectIndexCatalogKeys(
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

  private static StatusCode finishTransaction(
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
