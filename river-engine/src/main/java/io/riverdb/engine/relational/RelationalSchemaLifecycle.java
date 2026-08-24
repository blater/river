package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.EmbeddedDatabase;
import io.riverdb.engine.EmbeddedSessionOpenResult;
import io.riverdb.storage.heap.HeapRowResult;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import io.riverdb.tx.api.TransactionState;
import java.nio.ByteBuffer;

/** Owns explicit-session entry to table and index schema lifecycle operations. */
final class RelationalSchemaLifecycle {
  private static final int CATALOG_ROW_BYTES = CatalogRecord.MAXIMUM_BYTES;
  private static final int INDEX_BUILD_BATCH_ROWS = 48;

  private final EmbeddedDatabase embedded;
  private final RelationalSchemaGate schemaGate;
  private final RelationalTableLifecycle tables;
  private final RelationalIndexBuilder indexBuilder;
  private final RelationalIndexRemoval indexRemoval;
  private final RelationalIndexSchemaLifecycle indexSchemaLifecycle;
  private final RelationalIndexCreationFlow indexCreationFlow;
  private final HeapRowResult catalogRow = new HeapRowResult();
  private final ByteBuffer catalogScratch = ByteBuffer.allocateDirect(
      CatalogRecord.MAXIMUM_BYTES);
  private final ByteBuffer catalogOutput = ByteBuffer.allocateDirect(
      CatalogRecord.MAXIMUM_BYTES);
  private final RelationalKey.KeyResult catalogKey = new RelationalKey.KeyResult();
  private final CatalogIndexCodec.Result indexRecord = new CatalogIndexCodec.Result();
  private final TableDefinition indexedTable = new TableDefinition();
  private final TableDefinition indexStorageTable = new TableDefinition();
  private final CatalogSequenceCodec.IntResult nextTableId =
      new CatalogSequenceCodec.IntResult();
  private final RelationalScanCursor indexBuildCursor = new RelationalScanCursor();
  private final RelationalScanResult indexBuildRow = new RelationalScanResult();
  private final IndexBuildBatchState indexBuildBatchState = new IndexBuildBatchState();
  private long buildLastKey;
  private boolean buildBatchFull;

  RelationalSchemaLifecycle(EmbeddedDatabase database, RelationalSchemaGate gate) {
    embedded = database;
    schemaGate = gate;
    tables = new RelationalTableLifecycle(gate);
    indexBuilder = new RelationalIndexBuilder(gate);
    indexRemoval = new RelationalIndexRemoval(gate);
    indexSchemaLifecycle = new RelationalIndexSchemaLifecycle(
        gate,
        catalogRow,
        catalogScratch,
        catalogOutput,
        catalogKey,
        indexRecord,
        indexedTable,
        indexStorageTable,
        nextTableId);
    indexCreationFlow = new RelationalIndexCreationFlow(
        this, indexRemoval, indexStorageTable);
  }

  StatusCode renameTable(
      RelationalSession session,
      CharSequence currentName,
      CharSequence renamedName) {
    return tables.renameTable(session, currentName, renamedName);
  }

  StatusCode renameColumn(
      RelationalSession session,
      CharSequence tableName,
      CharSequence currentName,
      CharSequence renamedName) {
    return tables.renameColumn(session, tableName, currentName, renamedName);
  }

  StatusCode renameIndex(
      RelationalSession session,
      CharSequence currentName,
      CharSequence renamedName) {
    return indexRemoval.rename(session, currentName, renamedName);
  }

  synchronized StatusCode dropTable(
      CharSequence name,
      int maximumCleanupBatches) {
    RelationalSession session = newSession();
    if (session == null) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    return tables.drop(session, name, maximumCleanupBatches);
  }

  synchronized StatusCode markDroppingTable(
      RelationalSession session,
      CharSequence name) {
    return tables.markDropping(session, name);
  }

  StatusCode finishDroppingTable(
      RelationalSession session,
      CharSequence tableName,
      TransactionOutcome outcome) {
    return tables.finishDropping(session, tableName, outcome);
  }

  public synchronized StatusCode createUniqueValueIndex(
      CharSequence indexName,
      CharSequence tableName) {
    return createUniqueValueIndex(indexName, tableName, "value");
  }

  public synchronized StatusCode createUniqueValueIndex(
      CharSequence indexName,
      CharSequence tableName,
      CharSequence columnName) {
    return createValueIndex(
        indexName, tableName, columnName, Integer.MAX_VALUE, true);
  }

  public synchronized StatusCode createValueIndex(
      CharSequence indexName,
      CharSequence tableName,
      CharSequence columnName) {
    return createValueIndex(
        indexName, tableName, columnName, Integer.MAX_VALUE, false);
  }

  public synchronized StatusCode dropValueIndex(
      CharSequence indexName,
      CharSequence tableName) {
    return dropValueIndex(indexName, tableName, Integer.MAX_VALUE);
  }

  synchronized StatusCode dropValueIndex(
      CharSequence indexName,
      CharSequence tableName,
      int maximumCleanupBatches) {
    RelationalSession session = newSession();
    if (session == null) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    return indexRemoval.drop(
        session, indexName, tableName, maximumCleanupBatches);
  }

  StatusCode markDroppingValueIndex(
      RelationalSession session,
      CharSequence indexName,
      CharSequence tableName) {
    return indexRemoval.mark(session, indexName, tableName);
  }

  StatusCode finishDroppingValueIndex(
      RelationalSession session,
      CharSequence indexName,
      CharSequence tableName,
      TransactionOutcome outcome) {
    return indexRemoval.finish(session, indexName, tableName, outcome);
  }

  synchronized StatusCode createUniqueValueIndex(
      CharSequence indexName,
      CharSequence tableName,
      int maximumBuildBatches) {
    return createValueIndex(
        indexName, tableName, "value", maximumBuildBatches, true);
  }

  synchronized StatusCode createValueIndex(
      CharSequence indexName,
      CharSequence tableName,
      int maximumBuildBatches) {
    return createValueIndex(
        indexName, tableName, "value", maximumBuildBatches, false);
  }

  synchronized StatusCode createValueIndex(
      CharSequence indexName,
      CharSequence tableName,
      CharSequence columnName,
      int maximumBuildBatches,
      boolean unique) {
    return indexCreationFlow.create(
        indexName, tableName, columnName, maximumBuildBatches, unique);
  }

  private StatusCode buildBatchStatus = StatusCode.OK;

  StatusCode buildBatchStatus() {
    return buildBatchStatus;
  }

  StatusCode reserveIndexBuild(
      RelationalSession session,
      CharSequence indexName,
      CharSequence tableName,
      CharSequence columnName,
      boolean unique,
      TransactionOutcome outcome) {
    StatusCode status = session.begin(IsolationLevel.SERIALIZABLE);
    if (status.isOk()) {
      status = session.beginPersistentSchemaChange();
    }
    if (status.isOk()) {
      status = reserveOrResumeValueIndex(
          session, indexName, tableName, columnName, unique);
    }
    if (status.isOk()) {
      status = session.commitBuildPhase(outcome);
      if (status.isOk()) {
        status = publishBuildingSchema(session);
      }
      return status;
    }
    if (session.indexedSession().transaction().state() == TransactionState.ACTIVE) {
      StatusCode abort = session.abortBuildPhase(outcome);
      if (!abort.isOk()) {
        session.releasePersistentSchemaChange();
        return abort;
      }
    }
    return status;
  }

  boolean runIndexBuildBatches(
      RelationalSession session,
      CharSequence tableName,
      TransactionOutcome outcome,
      int maximumBuildBatches) {
    int batches = 0;
    long lowerKey = Long.MIN_VALUE;
    boolean complete = false;
    buildBatchStatus = StatusCode.OK;
    while (buildBatchStatus.isOk()
        && !complete
        && batches < maximumBuildBatches) {
      buildBatchStatus = buildUniqueValueIndexBatch(
          session, tableName, lowerKey, outcome);
      if (buildBatchStatus.isOk()) {
        complete = !buildBatchFull || buildLastKey == Long.MAX_VALUE;
        if (!complete) {
          lowerKey = buildLastKey + 1;
        }
        batches++;
      }
    }
    return complete;
  }

  private StatusCode reserveOrResumeValueIndex(
      RelationalSession session,
      CharSequence indexName,
      CharSequence tableName,
      CharSequence columnName,
      boolean unique) {
    return indexSchemaLifecycle.reserveOrResume(
        session, indexName, tableName, columnName, unique);
  }

  private StatusCode buildUniqueValueIndexBatch(
      RelationalSession session,
      CharSequence tableName,
      long lowerKey,
      TransactionOutcome outcome) {
    indexBuildBatchState.reset();
    StatusCode status = beginUniqueIndexBatch(session, tableName, lowerKey);
    if (status.isOk()) {
      status = scanUniqueIndexBatch(session, indexBuildBatchState);
    }
    status = closeIndexBuildCursor(session, status);
    indexBuildCursor.reset();
    buildBatchFull = status.isOk()
        && !indexBuildBatchState.exhausted
        && indexBuildBatchState.rows == INDEX_BUILD_BATCH_ROWS;
    return completeIndexBuildBatch(session, outcome, status);
  }

  private StatusCode beginUniqueIndexBatch(
      RelationalSession session,
      CharSequence tableName,
      long lowerKey) {
    StatusCode status = session.begin(IsolationLevel.REPEATABLE_READ);
    if (status.isOk()) {
      status = session.resolveTable(tableName, indexedTable);
    }
    if (status.isOk()
        && (!indexedTable.hasBuildingUniqueValueIndex()
            || indexedTable.uniqueValueIndexTableId() != indexStorageTable.tableId())) {
      return StatusCode.CORRUPTION;
    }
    return status.isOk()
        ? session.beginScanFrom(indexedTable, lowerKey, indexBuildCursor)
        : status;
  }

  private StatusCode scanUniqueIndexBatch(
      RelationalSession session,
      IndexBuildBatchState state) {
    while (state.rows < INDEX_BUILD_BATCH_ROWS) {
      StatusCode status = session.nextScan(indexBuildCursor, indexBuildRow);
      if (status == StatusCode.CONFLICT) {
        state.exhausted = true;
        return StatusCode.OK;
      }
      if (!status.isOk()) {
        return status;
      }
      status = indexBuildRow(session);
      if (!status.isOk()) {
        return status;
      }
      buildLastKey = indexBuildRow.key();
      state.rows++;
    }
    return StatusCode.OK;
  }

  private StatusCode indexBuildRow(RelationalSession session) {
    StatusCode status = copyIndexBuildRow();
    if (!status.isOk()) {
      return status;
    }
    int column = indexedTable.uniqueValueIndexColumn();
    boolean nullValue = (catalogScratch.getLong(indexedTable.nullMaskOffset())
        & 1L << column) != 0;
    return nullValue ? StatusCode.OK : indexBuildValue(session, column);
  }

  private StatusCode copyIndexBuildRow() {
    int bytes = indexBuildRow.row().length();
    if (bytes < indexedTable.fixedRowBytes()
        || bytes > indexedTable.maximumRowBytes()) {
      return StatusCode.CORRUPTION;
    }
    catalogScratch.clear();
    StatusCode status = indexBuildRow.row().copyTo(catalogScratch);
    if (!status.isOk()) {
      return status;
    }
    catalogScratch.flip();
    return indexedTable.isValidRow(catalogScratch)
        ? StatusCode.OK : StatusCode.CORRUPTION;
  }

  private StatusCode indexBuildValue(RelationalSession session, int column) {
    int slot = indexedTable.buildingIndexSlot();
    boolean unique = indexedTable.indexIsUnique(slot);
    StatusCode status;
    if (indexedTable.isVarchar(column)) {
      status = session.ensureTextIndexedValue(
          indexStorageTable,
          indexedTable,
          column,
          catalogScratch,
          indexBuildRow.key(),
          unique);
    } else {
      long value = catalogScratch.getLong((column - 1) * Long.BYTES);
      status = unique
          ? session.ensureIndexedValue(indexStorageTable, value, indexBuildRow.key())
          : session.ensureNonUniqueIndexedValue(
              indexStorageTable, value, indexBuildRow.key());
    }
    return status == StatusCode.CONFLICT && indexedTable.indexIsConstraint(slot)
        ? StatusCode.UNIQUE_VIOLATION : status;
  }

  private StatusCode closeIndexBuildCursor(
      RelationalSession session,
      StatusCode status) {
    if (!indexBuildCursor.isActive()) {
      return status;
    }
    StatusCode close = session.closeScan(indexBuildCursor);
    return status.isOk() ? close : status;
  }

  private StatusCode completeIndexBuildBatch(
      RelationalSession session,
      TransactionOutcome outcome,
      StatusCode status) {
    if (status.isOk()) {
      return session.commitBuildPhase(outcome);
    }
    if (session.indexedSession().transaction().state() != TransactionState.ACTIVE) {
      return status;
    }
    StatusCode abort = session.abortBuildPhase(outcome);
    return abort.isOk() ? status : abort;
  }

  StatusCode publishUniqueValueIndex(
      RelationalSession session,
      CharSequence indexName,
      CharSequence tableName,
      TransactionOutcome outcome) {
    return indexSchemaLifecycle.publish(session, indexName, tableName, outcome);
  }

  StatusCode buildUniqueValueIndex(
      RelationalSession session,
      CharSequence indexName,
      CharSequence tableName,
      CharSequence columnName) {
    return indexBuilder.build(
        session, indexName, tableName, columnName, true, false);
  }

  StatusCode buildValueIndex(
      RelationalSession session,
      CharSequence indexName,
      CharSequence tableName,
      CharSequence columnName,
      boolean unique,
      boolean constraint) {
    return indexBuilder.build(
        session, indexName, tableName, columnName, unique, constraint);
  }


  RelationalSession newSession() {
    EmbeddedSessionOpenResult result = new EmbeddedSessionOpenResult();
    StatusCode status = embedded.createSession(CATALOG_ROW_BYTES, result);
    return status.isOk()
        ? new RelationalSession(this, schemaGate, result.session()) : null;
  }

  private StatusCode publishBuildingSchema(RelationalSession owner) {
    StatusCode status = schemaGate.publishOwnedSchema(owner);
    if (status.isOk()) {
      indexStorageTable.set(
          schemaGate,
          indexedTable.uniqueValueIndexTableId(),
          0,
          TableDefinition.INDEX_NONE);
    }
    return status;
  }

  private static final class IndexBuildBatchState {
    private int rows;
    private boolean exhausted;

    private void reset() {
      rows = 0;
      exhausted = false;
    }
  }

}
