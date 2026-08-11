package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.EmbeddedDatabase;
import io.riverdb.engine.EmbeddedDatabaseOpenResult;
import io.riverdb.engine.EmbeddedSessionOpenResult;
import io.riverdb.engine.checkpoint.CheckpointResult;
import io.riverdb.storage.heap.HeapRowResult;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import io.riverdb.tx.api.TransactionState;
import java.nio.ByteBuffer;
import java.nio.file.Path;

/** Durable named-table catalog and logical table sessions over the embedded kernel. */
public final class RelationalDatabase {
  private static final int CATALOG_ROW_BYTES = CatalogRecord.MAXIMUM_BYTES;
  private static final int INDEX_BUILD_BATCH_ROWS = 48;

  private final EmbeddedDatabase embedded;
  private final HeapRowResult catalogRow = new HeapRowResult();
  private final ByteBuffer catalogScratch = ByteBuffer.allocateDirect(CATALOG_ROW_BYTES);
  private final ByteBuffer catalogOutput = ByteBuffer.allocateDirect(CATALOG_ROW_BYTES);
  private final RelationalKey.LongKeyResult catalogKey = new RelationalKey.LongKeyResult();
  private final CatalogRecord.IntResult nextTableId = new CatalogRecord.IntResult();
  private final CatalogRecord.IndexResult indexRecord = new CatalogRecord.IndexResult();
  private final TableDefinition indexedTable = new TableDefinition();
  private final TableDefinition indexStorageTable = new TableDefinition();
  private final TableDefinition updatedTable = new TableDefinition();
  private final RelationalScanCursor indexBuildCursor = new RelationalScanCursor();
  private final RelationalScanResult indexBuildRow = new RelationalScanResult();
  private final ByteBuffer indexKeyOutput = ByteBuffer.allocateDirect(Long.BYTES);
  private final long[] cleanupIndexKeys = new long[INDEX_BUILD_BATCH_ROWS];
  private long buildLastKey;
  private boolean buildBatchFull;
  private volatile long schemaVersion = 1;
  private RelationalSession schemaChangeOwner;
  private int activeTransactions;

  private RelationalDatabase(EmbeddedDatabase database) {
    embedded = database;
  }

  public static StatusCode create(
      Path directory,
      DatabaseIncarnation database,
      WalGeneration generation,
      int maximumActiveTransactions,
      RelationalDatabaseOpenResult result) {
    if (result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    EmbeddedDatabaseOpenResult embeddedResult = new EmbeddedDatabaseOpenResult();
    StatusCode status = EmbeddedDatabase.create(
        directory, database, generation, maximumActiveTransactions, embeddedResult);
    if (!status.isOk()) {
      return status;
    }
    RelationalDatabase relational = new RelationalDatabase(embeddedResult.database());
    status = relational.initializeCatalog();
    if (!status.isOk()) {
      relational.close();
      return status;
    }
    result.set(relational);
    return StatusCode.OK;
  }

  public static StatusCode openExisting(
      Path directory,
      DatabaseIncarnation database,
      WalGeneration generation,
      int maximumActiveTransactions,
      RelationalDatabaseOpenResult result) {
    if (result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    EmbeddedDatabaseOpenResult embeddedResult = new EmbeddedDatabaseOpenResult();
    StatusCode status = EmbeddedDatabase.openExisting(
        directory, database, generation, maximumActiveTransactions, embeddedResult);
    if (!status.isOk()) {
      return status;
    }
    RelationalDatabase relational = new RelationalDatabase(embeddedResult.database());
    status = relational.validateCatalog();
    if (!status.isOk()) {
      relational.close();
      return status;
    }
    result.set(relational);
    return StatusCode.OK;
  }

  public synchronized StatusCode createTable(CharSequence name, TableDefinition result) {
    return createTable(name, "key", "value", result);
  }

  public synchronized StatusCode createTable(
      CharSequence name,
      CharSequence keyColumnName,
      CharSequence valueColumnName,
      TableDefinition result) {
    if (!RelationalKey.validName(name)
        || !RelationalKey.validName(keyColumnName)
        || !RelationalKey.validName(valueColumnName)
        || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    RelationalSession session = newSession();
    if (session == null) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    TransactionOutcome outcome = new TransactionOutcome();
    StatusCode status = session.begin(IsolationLevel.SERIALIZABLE);
    if (status.isOk()) {
      status = session.createTable(name, keyColumnName, valueColumnName, result);
    }
    if (status.isOk()) {
      status = session.commit(outcome);
    } else if (session.indexedSession().transaction().state() == TransactionState.ACTIVE) {
      session.abort(outcome);
    }
    return status;
  }

  public synchronized StatusCode createTable(
      CharSequence name,
      TableSchema schema,
      TableDefinition result) {
    if (!RelationalKey.validName(name)
        || schema == null
        || !schema.isValid()
        || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    RelationalSession session = newSession();
    if (session == null) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    TransactionOutcome outcome = new TransactionOutcome();
    StatusCode status = session.begin(IsolationLevel.SERIALIZABLE);
    if (status.isOk()) {
      status = session.createTable(name, schema, result);
    }
    if (status.isOk()) {
      status = session.commit(outcome);
    } else if (session.indexedSession().transaction().state() == TransactionState.ACTIVE) {
      session.abort(outcome);
    }
    return status;
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

  private synchronized StatusCode createValueIndex(
      CharSequence indexName,
      CharSequence tableName,
      CharSequence columnName,
      int maximumBuildBatches,
      boolean unique) {
    if (!RelationalKey.validName(indexName)
        || !RelationalKey.validName(tableName)
        || !RelationalKey.validName(columnName)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (maximumBuildBatches <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    RelationalSession session = newSession();
    if (session == null) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    TransactionOutcome outcome = new TransactionOutcome();
    boolean buildReserved = false;
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
      buildReserved = status.isOk();
      if (status.isOk()) {
        status = publishBuildingSchema(session);
      }
    } else if (session.indexedSession().transaction().state() == TransactionState.ACTIVE) {
      StatusCode abort = session.abortBuildPhase(outcome);
      if (!abort.isOk()) {
        session.releasePersistentSchemaChange();
        return abort;
      }
    }
    int batches = 0;
    long lowerKey = 0;
    boolean complete = false;
    while (status.isOk() && !complete && batches < maximumBuildBatches) {
      status = buildUniqueValueIndexBatch(
          session, tableName, lowerKey, outcome);
      if (status.isOk()) {
        complete = !buildBatchFull
            || buildLastKey == RelationalKey.MAXIMUM_USER_KEY;
        lowerKey = buildLastKey == RelationalKey.MAXIMUM_USER_KEY
            ? RelationalKey.USER_KEY_MASK : buildLastKey + 1;
        batches++;
      }
    }
    if (status.isOk() && !complete) {
      session.releasePersistentSchemaChange();
      return StatusCode.RETRY;
    }
    if (status.isOk()) {
      return publishUniqueValueIndex(
          session, indexName, tableName, outcome);
    }
    if (!status.isOk() && buildReserved) {
      StatusCode cleanup = cleanupUniqueValueIndex(
          session, indexName, tableName, outcome);
      return cleanup.isOk() ? status : cleanup;
    }
    session.releasePersistentSchemaChange();
    return status;
  }

  private StatusCode reserveOrResumeValueIndex(
      RelationalSession session,
      CharSequence indexName,
      CharSequence tableName,
      CharSequence columnName,
      boolean unique) {
    StatusCode status = session.resolveTable(tableName, indexedTable);
    int indexColumn = status.isOk() ? indexedTable.findColumn(columnName) : -1;
    if (status.isOk() && indexColumn <= 0) {
      status = StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (!status.isOk()) {
      return status;
    }
    if (indexedTable.hasIndexOn(indexColumn)) {
      return StatusCode.CONFLICT;
    }
    if (indexedTable.uniqueIndexCount() >= TableDefinition.MAXIMUM_INDEXES
        && !indexedTable.hasBuildingUniqueValueIndex()) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    status = RelationalKey.catalogTableKey(indexName, catalogKey);
    if (!status.isOk()) {
      return status;
    }
    status = session.indexedSession().fetchByKey(catalogKey.key(), catalogRow);
    int indexTableId = 0;
    if (status.isOk()) {
      status = CatalogRecord.decodeIndex(
          catalogRow, catalogScratch, indexName, indexRecord);
      if (status.isOk()
          && (indexRecord.state() != TableDefinition.INDEX_BUILDING
              || indexRecord.tableId() != indexedTable.tableId()
              || !indexedTable.hasBuildingUniqueValueIndex()
              || indexedTable.uniqueValueIndexColumn() != indexColumn
              || indexedTable.uniqueValueIndexTableId() != indexRecord.indexTableId()
              || indexRecord.isUnique() != unique)) {
        status = StatusCode.CONFLICT;
      }
      indexTableId = status.isOk() ? indexRecord.indexTableId() : 0;
    } else if (status == StatusCode.CONFLICT) {
      if (indexedTable.hasBuildingUniqueValueIndex()) {
        return StatusCode.CORRUPTION;
      }
      status = session.indexedSession().fetchByKey(
          RelationalKey.CATALOG_SEQUENCE_KEY, catalogRow);
      if (status.isOk()) {
        status = CatalogRecord.decodeSequence(catalogRow, catalogScratch, nextTableId);
      }
      indexTableId = nextTableId.value();
      if (status.isOk() && indexTableId > RelationalKey.MAXIMUM_TABLE_ID) {
        status = StatusCode.RESOURCE_EXHAUSTED;
      }
      if (status.isOk()) {
        CatalogRecord.encodeSequence(catalogOutput, indexTableId + 1);
        status = session.indexedSession().update(
            RelationalKey.CATALOG_SEQUENCE_KEY, catalogOutput);
      }
      if (status.isOk()) {
        status = RelationalKey.catalogTableKey(tableName, catalogKey);
      }
      if (status.isOk()) {
        CatalogRecord.encodeTable(
            catalogOutput,
            indexedTable.tableId(),
            indexTableId,
            TableDefinition.INDEX_BUILDING,
            indexColumn,
            tableName,
            indexedTable,
            unique);
        status = session.indexedSession().update(catalogKey.key(), catalogOutput);
      }
      if (status.isOk()) {
        status = RelationalKey.catalogTableKey(indexName, catalogKey);
      }
      if (status.isOk()) {
        CatalogRecord.encodeIndex(
            catalogOutput,
            indexedTable.tableId(),
            indexTableId,
            TableDefinition.INDEX_BUILDING,
            indexName,
            unique);
        status = session.indexedSession().insert(catalogKey.key(), catalogOutput);
      }
      if (status.isOk()) {
        indexedTable.set(
            this,
            indexedTable.tableId(),
            indexTableId,
            TableDefinition.INDEX_BUILDING,
            indexColumn,
            indexedTable,
            unique);
      }
    }
    if (status.isOk()) {
      indexStorageTable.set(
          this, indexTableId, 0, TableDefinition.INDEX_NONE);
    }
    return status;
  }

  private StatusCode buildUniqueValueIndexBatch(
      RelationalSession session,
      CharSequence tableName,
      long lowerKey,
      TransactionOutcome outcome) {
    StatusCode status = session.begin(IsolationLevel.REPEATABLE_READ);
    if (status.isOk()) {
      status = session.resolveTable(tableName, indexedTable);
    }
    if (status.isOk()
        && (!indexedTable.hasBuildingUniqueValueIndex()
            || indexedTable.uniqueValueIndexTableId() != indexStorageTable.tableId())) {
      status = StatusCode.CORRUPTION;
    }
    if (status.isOk()) {
      status = session.beginScan(
          indexedTable,
          lowerKey,
          RelationalKey.USER_KEY_MASK,
          indexBuildCursor);
    }
    int rows = 0;
    boolean exhausted = false;
    while (status.isOk() && rows < INDEX_BUILD_BATCH_ROWS) {
      status = session.nextScan(indexBuildCursor, indexBuildRow);
      if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
        exhausted = true;
        break;
      }
      if (status.isOk()
          && (indexBuildRow.row().length() != indexedTable.rowBytes()
              || !indexedTable.isValidNullMask(
                  indexBuildRow.row().getLong(indexedTable.nullMaskOffset())))) {
        status = StatusCode.CORRUPTION;
      }
      if (status.isOk()) {
        catalogScratch.clear();
        status = indexBuildRow.row().copyTo(catalogScratch);
      }
      boolean nullValue = status.isOk()
          && (catalogScratch.getLong(indexedTable.nullMaskOffset())
              & 1L << indexedTable.uniqueValueIndexColumn()) != 0;
      if (status.isOk() && !nullValue) {
        long value = catalogScratch.getLong(
            (indexedTable.uniqueValueIndexColumn() - 1) * Long.BYTES);
        int buildingSlot = indexedTable.buildingIndexSlot();
        status = indexedTable.indexIsUnique(buildingSlot)
            ? session.ensureIndexedValue(
                indexStorageTable, value, indexBuildRow.key())
            : session.ensureNonUniqueIndexedValue(
                indexStorageTable, value, indexBuildRow.key());
      }
      if (status.isOk()) {
        buildLastKey = indexBuildRow.key();
        rows++;
      }
    }
    if (indexBuildCursor.isActive()) {
      StatusCode close = session.closeScan(indexBuildCursor);
      if (status.isOk()) {
        status = close;
      }
    }
    indexBuildCursor.reset();
    buildBatchFull = status.isOk() && !exhausted && rows == INDEX_BUILD_BATCH_ROWS;
    if (status.isOk()) {
      status = session.commitBuildPhase(outcome);
    } else if (session.indexedSession().transaction().state() == TransactionState.ACTIVE) {
      StatusCode abort = session.abortBuildPhase(outcome);
      if (!abort.isOk()) {
        return abort;
      }
    }
    return status;
  }

  private StatusCode publishUniqueValueIndex(
      RelationalSession session,
      CharSequence indexName,
      CharSequence tableName,
      TransactionOutcome outcome) {
    StatusCode status = session.begin(IsolationLevel.SERIALIZABLE);
    if (status.isOk()) {
      status = session.resolveTable(tableName, indexedTable);
    }
    if (status.isOk()
        && (!indexedTable.hasBuildingUniqueValueIndex()
            || indexedTable.uniqueValueIndexTableId() != indexStorageTable.tableId())) {
      status = StatusCode.CORRUPTION;
    }
    if (status.isOk()) {
      status = RelationalKey.catalogTableKey(tableName, catalogKey);
    }
    if (status.isOk()) {
      int buildingSlot = indexedTable.buildingIndexSlot();
      CatalogRecord.encodeTable(
          catalogOutput,
          indexedTable.tableId(),
          indexStorageTable.tableId(),
          TableDefinition.INDEX_READY,
          indexedTable.uniqueValueIndexColumn(),
          tableName,
          indexedTable,
          indexedTable.indexIsUnique(buildingSlot));
      status = session.indexedSession().update(catalogKey.key(), catalogOutput);
    }
    if (status.isOk()) {
      status = RelationalKey.catalogTableKey(indexName, catalogKey);
    }
    if (status.isOk()) {
      int buildingSlot = indexedTable.buildingIndexSlot();
      CatalogRecord.encodeIndex(
          catalogOutput,
          indexedTable.tableId(),
          indexStorageTable.tableId(),
          TableDefinition.INDEX_READY,
          indexName,
          indexedTable.indexIsUnique(buildingSlot));
      status = session.indexedSession().update(catalogKey.key(), catalogOutput);
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

  private StatusCode cleanupUniqueValueIndex(
      RelationalSession session,
      CharSequence indexName,
      CharSequence tableName,
      TransactionOutcome outcome) {
    StatusCode status = StatusCode.OK;
    boolean complete = false;
    while (status.isOk() && !complete) {
      status = session.begin(IsolationLevel.REPEATABLE_READ);
      if (status.isOk()) {
        status = session.beginScan(indexStorageTable, indexBuildCursor);
      }
      int count = 0;
      boolean exhausted = false;
      while (status.isOk() && count < cleanupIndexKeys.length) {
        status = session.nextScan(indexBuildCursor, indexBuildRow);
        if (status == StatusCode.CONFLICT) {
          status = StatusCode.OK;
          exhausted = true;
          break;
        }
        if (status.isOk()) {
          cleanupIndexKeys[count++] = indexBuildRow.key();
        }
      }
      if (indexBuildCursor.isActive()) {
        StatusCode close = session.closeScan(indexBuildCursor);
        if (status.isOk()) {
          status = close;
        }
      }
      indexBuildCursor.reset();
      for (int index = 0; status.isOk() && index < count; index++) {
        status = session.delete(indexStorageTable, cleanupIndexKeys[index]);
        cleanupIndexKeys[index] = 0;
      }
      if (status.isOk()) {
        status = session.commitBuildPhase(outcome);
      } else if (session.indexedSession().transaction().state() == TransactionState.ACTIVE) {
        StatusCode abort = session.abortBuildPhase(outcome);
        if (!abort.isOk()) {
          status = abort;
        }
      }
      complete = status.isOk() && exhausted;
    }
    if (status.isOk()) {
      status = session.begin(IsolationLevel.SERIALIZABLE);
    }
    if (status.isOk()) {
      status = session.resolveTable(tableName, indexedTable);
    }
    if (status.isOk()) {
      updatedTable.set(
          this,
          indexedTable.tableId(),
          0,
          TableDefinition.INDEX_NONE,
          -1,
          indexedTable);
      status = updatedTable.removeIndex(indexStorageTable.tableId());
    }
    if (status.isOk()) {
      status = RelationalKey.catalogTableKey(tableName, catalogKey);
    }
    if (status.isOk()) {
      CatalogRecord.encodeTable(
          catalogOutput,
          indexedTable.tableId(),
          0,
          TableDefinition.INDEX_NONE,
          -1,
          tableName,
          updatedTable);
      status = session.indexedSession().update(catalogKey.key(), catalogOutput);
    }
    if (status.isOk()) {
      status = RelationalKey.catalogTableKey(indexName, catalogKey);
    }
    if (status.isOk()) {
      status = session.indexedSession().delete(catalogKey.key());
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

  StatusCode buildUniqueValueIndex(
      RelationalSession session,
      CharSequence indexName,
      CharSequence tableName,
      CharSequence columnName) {
    return buildValueIndex(session, indexName, tableName, columnName, true);
  }

  StatusCode buildValueIndex(
      RelationalSession session,
      CharSequence indexName,
      CharSequence tableName,
      CharSequence columnName,
      boolean unique) {
    StatusCode status = session.resolveTable(tableName, indexedTable);
    int indexColumn = status.isOk() ? indexedTable.findColumn(columnName) : -1;
    if (status.isOk() && indexColumn <= 0) {
      status = StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (status.isOk() && indexedTable.hasIndexOn(indexColumn)) {
      status = StatusCode.CONFLICT;
    }
    if (status.isOk() && indexedTable.hasBuildingUniqueValueIndex()) {
      status = StatusCode.RETRY;
    }
    if (status.isOk()
        && indexedTable.uniqueIndexCount() >= TableDefinition.MAXIMUM_INDEXES) {
      status = StatusCode.RESOURCE_EXHAUSTED;
    }
    if (status.isOk()) {
      status = RelationalKey.catalogTableKey(indexName, catalogKey);
    }
    if (status.isOk()) {
      status = session.indexedSession().fetchByKey(catalogKey.key(), catalogRow);
      if (status.isOk()) {
        status = StatusCode.CONFLICT;
      } else if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
      }
    }
    if (status.isOk()) {
      status = session.indexedSession().fetchByKey(
          RelationalKey.CATALOG_SEQUENCE_KEY, catalogRow);
    }
    if (status.isOk()) {
      status = CatalogRecord.decodeSequence(catalogRow, catalogScratch, nextTableId);
    }
    int indexTableId = nextTableId.value();
    if (status.isOk() && indexTableId > RelationalKey.MAXIMUM_TABLE_ID) {
      status = StatusCode.RESOURCE_EXHAUSTED;
    }
    if (status.isOk()) {
      indexStorageTable.set(
          this, indexTableId, 0, TableDefinition.INDEX_NONE);
      status = session.beginScan(indexedTable, indexBuildCursor);
    }
    boolean scanActive = status.isOk();
    while (status.isOk()) {
      status = session.nextScan(indexBuildCursor, indexBuildRow);
      if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
        break;
      }
      if (status.isOk()
          && (indexBuildRow.row().length() != indexedTable.rowBytes()
              || !indexedTable.isValidNullMask(
                  indexBuildRow.row().getLong(indexedTable.nullMaskOffset())))) {
        status = StatusCode.CORRUPTION;
      }
      if (status.isOk()) {
        catalogScratch.clear();
        status = indexBuildRow.row().copyTo(catalogScratch);
      }
      boolean nullValue = status.isOk()
          && (catalogScratch.getLong(indexedTable.nullMaskOffset())
              & 1L << indexColumn) != 0;
      if (status.isOk() && !nullValue) {
        long value = catalogScratch.getLong(
            (indexColumn - 1) * Long.BYTES);
        if (unique) {
          indexKeyOutput.clear();
          indexKeyOutput.putLong(0, indexBuildRow.key());
          indexKeyOutput.position(0);
          indexKeyOutput.limit(Long.BYTES);
          status = session.insertIndexedValue(indexStorageTable, value, indexKeyOutput);
        } else {
          status = session.insertNonUniqueIndexedValue(
              indexStorageTable, value, indexBuildRow.key());
        }
      }
    }
    if (scanActive) {
      StatusCode close = session.closeScan(indexBuildCursor);
      if (status.isOk()) {
        status = close;
      }
    }
    if (status.isOk()) {
      CatalogRecord.encodeSequence(catalogOutput, indexTableId + 1);
      status = session.indexedSession().update(
          RelationalKey.CATALOG_SEQUENCE_KEY, catalogOutput);
    }
    if (status.isOk()) {
      status = RelationalKey.catalogTableKey(tableName, catalogKey);
    }
    if (status.isOk()) {
      CatalogRecord.encodeTable(
          catalogOutput,
          indexedTable.tableId(),
          indexTableId,
          TableDefinition.INDEX_READY,
          indexColumn,
          tableName,
          indexedTable,
          unique);
      status = session.indexedSession().update(catalogKey.key(), catalogOutput);
    }
    if (status.isOk()) {
      status = RelationalKey.catalogTableKey(indexName, catalogKey);
    }
    if (status.isOk()) {
      CatalogRecord.encodeIndex(
          catalogOutput,
          indexedTable.tableId(),
          indexTableId,
          TableDefinition.INDEX_READY,
          indexName,
          unique);
      status = session.indexedSession().insert(catalogKey.key(), catalogOutput);
    }
    indexBuildCursor.reset();
    return status;
  }

  public StatusCode createSession(RelationalSessionOpenResult result) {
    if (result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    RelationalSession session = newSession();
    if (session == null) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    result.set(session);
    return StatusCode.OK;
  }

  public StatusCode vacuum(TransactionOutcome result) {
    return embedded.vacuum(result);
  }

  public StatusCode checkpoint(CheckpointResult result) {
    return embedded.checkpoint(result);
  }

  public StatusCode close() {
    return embedded.close();
  }

  private StatusCode initializeCatalog() {
    RelationalSession session = newSession();
    if (session == null) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    TransactionOutcome outcome = new TransactionOutcome();
    StatusCode status = session.begin(IsolationLevel.SERIALIZABLE);
    if (status.isOk()) {
      CatalogRecord.encodeSequence(catalogOutput, 1);
      status = session.indexedSession().insert(
          RelationalKey.CATALOG_SEQUENCE_KEY, catalogOutput);
    }
    if (status.isOk()) {
      status = session.commit(outcome);
    } else if (session.indexedSession().transaction().state() == TransactionState.ACTIVE) {
      session.abort(outcome);
    }
    return status;
  }

  private StatusCode validateCatalog() {
    RelationalSession session = newSession();
    if (session == null) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    TransactionOutcome outcome = new TransactionOutcome();
    StatusCode status = session.begin(IsolationLevel.REPEATABLE_READ);
    if (status.isOk()) {
      status = session.indexedSession().fetchByKey(
          RelationalKey.CATALOG_SEQUENCE_KEY, catalogRow);
    }
    if (status.isOk()) {
      status = CatalogRecord.decodeSequence(catalogRow, catalogScratch, nextTableId);
    }
    StatusCode terminal = session.abort(outcome);
    return status.isOk() ? terminal : status;
  }

  private RelationalSession newSession() {
    EmbeddedSessionOpenResult result = new EmbeddedSessionOpenResult();
    StatusCode status = embedded.createSession(CATALOG_ROW_BYTES, result);
    return status.isOk() ? new RelationalSession(this, result.session()) : null;
  }

  synchronized StatusCode enterTransaction(RelationalSession requester) {
    if (requester == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (schemaChangeOwner != null && schemaChangeOwner != requester) {
      return StatusCode.RETRY;
    }
    activeTransactions++;
    return StatusCode.OK;
  }

  synchronized void leaveTransaction() {
    activeTransactions--;
  }

  synchronized StatusCode beginSchemaChange(RelationalSession owner) {
    if (owner == null || schemaChangeOwner != null || activeTransactions != 1) {
      return StatusCode.RETRY;
    }
    schemaChangeOwner = owner;
    return StatusCode.OK;
  }

  synchronized void completeSchemaChange(RelationalSession owner, boolean committed) {
    if (schemaChangeOwner == owner) {
      if (committed) {
        schemaVersion++;
      }
      schemaChangeOwner = null;
    }
  }

  private synchronized StatusCode publishBuildingSchema(RelationalSession owner) {
    if (schemaChangeOwner != owner) {
      return StatusCode.NOT_OWNER;
    }
    schemaVersion++;
    indexStorageTable.set(
        this,
        indexedTable.uniqueValueIndexTableId(),
        0,
        TableDefinition.INDEX_NONE);
    return StatusCode.OK;
  }

  long schemaVersion() {
    return schemaVersion;
  }
}
