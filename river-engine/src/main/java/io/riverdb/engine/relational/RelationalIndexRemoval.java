package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.table.IndexedScanCursor;
import io.riverdb.engine.table.IndexedScanResult;
import io.riverdb.storage.heap.HeapRowResult;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import io.riverdb.tx.api.TransactionState;
import java.nio.ByteBuffer;

/** Durable marking, bounded row deletion, and catalog removal for one index. */
final class RelationalIndexRemoval {
  private static final int BATCH_ROWS = 48;

  private final RelationalSchemaGate schemaGate;
  private final HeapRowResult catalogRow = new HeapRowResult();
  private final ByteBuffer scratch = ByteBuffer.allocateDirect(CatalogRecord.MAXIMUM_BYTES);
  private final ByteBuffer output = ByteBuffer.allocateDirect(CatalogRecord.MAXIMUM_BYTES);
  private final RelationalKey.KeyResult catalogKey = new RelationalKey.KeyResult();
  private final CatalogIndexCodec.Result indexRecord = new CatalogIndexCodec.Result();
  private final TableDefinition table = new TableDefinition();
  private final TableDefinition indexTable = new TableDefinition();
  private final TableDefinition updatedTable = new TableDefinition();
  private final IndexedScanCursor scanCursor = new IndexedScanCursor();
  private final IndexedScanResult scanRow = new IndexedScanResult();
  private final long[] rowSpaces = new long[BATCH_ROWS];
  private final long[] rowKeys = new long[BATCH_ROWS];
  private boolean alreadyMarked;
  private boolean batchComplete;

  RelationalIndexRemoval(RelationalSchemaGate gate) {
    schemaGate = gate;
  }

  StatusCode rename(
      RelationalSession session,
      CharSequence currentName,
      CharSequence renamedName) {
    StatusCode status = RelationalKey.catalogTableKey(currentName, catalogKey);
    if (status.isOk()) {
      status = session.indexedSession().fetchByKey(
          catalogKey.space(), catalogKey.key(), catalogRow);
    }
    if (status.isOk()) {
      status = CatalogIndexCodec.decode(catalogRow, scratch, currentName, indexRecord);
    }
    if (status.isOk() && indexRecord.state() != TableDefinition.INDEX_READY) {
      status = StatusCode.RETRY;
    }
    if (status.isOk() && indexRecord.isConstraint()) {
      status = StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (status.isOk()) {
      status = RelationalKey.catalogTableKey(renamedName, catalogKey);
    }
    if (status.isOk()) {
      status = requireAbsent(session);
    }
    if (status.isOk()) {
      CatalogIndexCodec.encode(
          output,
          indexRecord.tableId(),
          indexRecord.indexTableId(),
          indexRecord.state(),
          renamedName,
          indexRecord.isUnique(),
          indexRecord.isConstraint());
      status = session.indexedSession().insert(catalogKey.space(), catalogKey.key(), output);
    }
    if (status.isOk()) {
      status = RelationalKey.catalogTableKey(currentName, catalogKey);
    }
    return status.isOk()
        ? session.indexedSession().delete(catalogKey.space(), catalogKey.key()) : status;
  }

  StatusCode drop(
      RelationalSession session,
      CharSequence indexName,
      CharSequence tableName,
      int maximumBatches) {
    if (!RelationalKey.validName(indexName)
        || !RelationalKey.validName(tableName)
        || maximumBatches <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    TransactionOutcome outcome = new TransactionOutcome();
    StatusCode status = session.begin(IsolationLevel.SERIALIZABLE);
    if (status.isOk()) {
      status = session.beginPersistentSchemaChange();
    }
    if (status.isOk()) {
      status = mark(session, indexName, tableName);
    }
    if (session.indexedSession().transaction().state() == TransactionState.ACTIVE) {
      StatusCode terminal = status.isOk() && !alreadyMarked
          ? session.commitBuildPhase(outcome) : session.abortBuildPhase(outcome);
      if (status.isOk()) {
        status = terminal;
      }
    }
    if (status.isOk() && !alreadyMarked) {
      status = publishDropping(session);
    }
    if (!status.isOk()) {
      session.releasePersistentSchemaChange();
      return status;
    }
    return cleanup(session, indexName, tableName, outcome, maximumBatches);
  }

  StatusCode mark(
      RelationalSession session,
      CharSequence indexName,
      CharSequence tableName) {
    alreadyMarked = false;
    StatusCode status = resolveForDrop(session, indexName, tableName);
    int indexTableId = status.isOk() ? indexRecord.indexTableId() : 0;
    int slot = status.isOk() ? indexSlot(indexTableId) : -1;
    if (status.isOk()) {
      status = validateForDrop(slot);
    }
    if (!status.isOk()) {
      return status;
    }
    indexTable.set(schemaGate, indexTableId, 0, TableDefinition.INDEX_NONE);
    alreadyMarked = indexRecord.state() == TableDefinition.INDEX_DROPPING;
    return alreadyMarked
        ? StatusCode.OK
        : markCatalogs(session, indexName, tableName, indexTableId, slot);
  }

  StatusCode finish(
      RelationalSession session,
      CharSequence indexName,
      CharSequence tableName,
      TransactionOutcome outcome) {
    StatusCode status = publishDropping(session);
    return status.isOk()
        ? cleanup(session, indexName, tableName, outcome, Integer.MAX_VALUE)
        : status;
  }

  private StatusCode resolveForDrop(
      RelationalSession session,
      CharSequence indexName,
      CharSequence tableName) {
    StatusCode status = session.resolveTable(tableName, table);
    if (status.isOk()) {
      status = RelationalKey.catalogTableKey(indexName, catalogKey);
    }
    if (status.isOk()) {
      status = session.indexedSession().fetchByKey(
          catalogKey.space(), catalogKey.key(), catalogRow);
    }
    return status.isOk()
        ? CatalogIndexCodec.decode(catalogRow, scratch, indexName, indexRecord)
        : status;
  }

  private int indexSlot(int indexTableId) {
    for (int slot = 0; slot < table.uniqueIndexCount(); slot++) {
      if (table.uniqueIndexTableId(slot) == indexTableId) {
        return slot;
      }
    }
    return -1;
  }

  private StatusCode validateForDrop(int slot) {
    if (indexRecord.tableId() != table.tableId() || slot < 0) {
      return StatusCode.CONFLICT;
    }
    if (indexRecord.state() != table.uniqueIndexState(slot)
        || indexRecord.isUnique() != table.indexIsUnique(slot)
        || indexRecord.isConstraint() != table.indexIsConstraint(slot)) {
      return StatusCode.CORRUPTION;
    }
    return indexRecord.isConstraint()
        ? StatusCode.INVALID_EXTERNAL_INPUT : StatusCode.OK;
  }

  private StatusCode markCatalogs(
      RelationalSession session,
      CharSequence indexName,
      CharSequence tableName,
      int indexTableId,
      int slot) {
    StatusCode status = RelationalKey.catalogTableKey(tableName, catalogKey);
    if (status.isOk()) {
      CatalogRecord.encodeTable(
          output,
          table.tableId(),
          indexTableId,
          TableDefinition.INDEX_DROPPING,
          table.uniqueIndexColumn(slot),
          tableName,
          table,
          table.indexIsUnique(slot));
      status = session.indexedSession().update(catalogKey.space(), catalogKey.key(), output);
    }
    if (status.isOk()) {
      status = RelationalKey.catalogTableKey(indexName, catalogKey);
    }
    if (status.isOk()) {
      CatalogIndexCodec.encode(
          output,
          table.tableId(),
          indexTableId,
          TableDefinition.INDEX_DROPPING,
          indexName,
          table.indexIsUnique(slot));
      status = session.indexedSession().update(catalogKey.space(), catalogKey.key(), output);
    }
    return status;
  }

  StatusCode cleanup(
      RelationalSession session,
      CharSequence indexName,
      CharSequence tableName,
      TransactionOutcome outcome,
      int maximumBatches) {
    StatusCode status = StatusCode.OK;
    boolean complete = false;
    for (int batch = 0;
        status.isOk() && !complete && batch < maximumBatches;
        batch++) {
      status = cleanupBatch(session, outcome);
      complete = status.isOk() && batchComplete;
    }
    if (status.isOk() && !complete) {
      session.releasePersistentSchemaChange();
      return StatusCode.RETRY;
    }
    if (status.isOk()) {
      status = removeCatalog(session, indexName, tableName);
    }
    if (status.isOk()) {
      return session.commit(outcome);
    }
    if (session.indexedSession().transaction().state() == TransactionState.ACTIVE) {
      StatusCode abort = session.abort(outcome);
      if (!abort.isOk()) {
        return abort;
      }
    } else {
      session.releasePersistentSchemaChange();
    }
    return status;
  }

  StatusCode cleanupFailedBuild(
      RelationalSession session,
      CharSequence indexName,
      CharSequence tableName,
      TransactionOutcome outcome,
      int maximumBatches,
      int indexTableId) {
    indexTable.set(schemaGate, indexTableId, 0, TableDefinition.INDEX_NONE);
    return cleanup(session, indexName, tableName, outcome, maximumBatches);
  }

  private StatusCode cleanupBatch(
      RelationalSession session, TransactionOutcome outcome) {
    batchComplete = false;
    StatusCode status = session.begin(IsolationLevel.REPEATABLE_READ);
    if (status.isOk()) {
      long dataSpace = RelationalKey.dataSpace(indexTable.tableId());
      status = session.indexedSession().beginScan(
          dataSpace,
          Long.MIN_VALUE,
          RelationalKey.auxiliarySpace(indexTable.tableId()) + 1,
          Long.MIN_VALUE,
          scanCursor);
    }
    int count = 0;
    while (status.isOk() && count < rowKeys.length) {
      status = session.indexedSession().nextScan(scanCursor, scanRow);
      if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
        batchComplete = true;
        break;
      }
      if (status.isOk()) {
        rowSpaces[count] = scanRow.keySpace();
        rowKeys[count++] = scanRow.key();
      }
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
      return session.commitBuildPhase(outcome);
    }
    if (session.indexedSession().transaction().state() != TransactionState.ACTIVE) {
      return status;
    }
    StatusCode abort = session.abortBuildPhase(outcome);
    return abort.isOk() ? status : abort;
  }

  private StatusCode removeCatalog(
      RelationalSession session,
      CharSequence indexName,
      CharSequence tableName) {
    StatusCode status = session.begin(IsolationLevel.SERIALIZABLE);
    if (status.isOk()) {
      status = session.resolveTable(tableName, table);
    }
    if (status.isOk()) {
      updatedTable.set(
          schemaGate,
          table.tableId(),
          0,
          TableDefinition.INDEX_NONE,
          -1,
          table);
      status = updatedTable.removeIndex(indexTable.tableId());
    }
    if (status.isOk()) {
      status = RelationalKey.catalogTableKey(tableName, catalogKey);
    }
    if (status.isOk()) {
      CatalogRecord.encodeTable(
          output,
          table.tableId(),
          0,
          TableDefinition.INDEX_NONE,
          -1,
          tableName,
          updatedTable);
      status = session.indexedSession().update(catalogKey.space(), catalogKey.key(), output);
    }
    if (status.isOk()) {
      status = RelationalKey.catalogTableKey(indexName, catalogKey);
    }
    return status.isOk()
        ? session.indexedSession().delete(catalogKey.space(), catalogKey.key()) : status;
  }

  private StatusCode publishDropping(RelationalSession owner) {
    StatusCode status = schemaGate.publishOwnedSchema(owner);
    if (status.isOk()) {
      int tableId = indexTable.tableId();
      indexTable.set(schemaGate, tableId, 0, TableDefinition.INDEX_NONE);
    }
    return status;
  }

  private StatusCode requireAbsent(RelationalSession session) {
    StatusCode status = session.indexedSession().fetchByKey(
        catalogKey.space(), catalogKey.key(), catalogRow);
    if (status.isOk()) {
      return StatusCode.CONFLICT;
    }
    return status == StatusCode.CONFLICT ? StatusCode.OK : status;
  }
}
