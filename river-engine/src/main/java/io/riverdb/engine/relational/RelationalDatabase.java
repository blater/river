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

  private final EmbeddedDatabase embedded;
  private final HeapRowResult catalogRow = new HeapRowResult();
  private final ByteBuffer catalogScratch = ByteBuffer.allocateDirect(CATALOG_ROW_BYTES);
  private final ByteBuffer catalogOutput = ByteBuffer.allocateDirect(CATALOG_ROW_BYTES);
  private final RelationalKey.LongKeyResult catalogKey = new RelationalKey.LongKeyResult();
  private final CatalogRecord.IntResult nextTableId = new CatalogRecord.IntResult();
  private final TableDefinition indexedTable = new TableDefinition();
  private final TableDefinition indexStorageTable = new TableDefinition();
  private final RelationalScanCursor indexBuildCursor = new RelationalScanCursor();
  private final RelationalScanResult indexBuildRow = new RelationalScanResult();
  private final ByteBuffer indexKeyOutput = ByteBuffer.allocateDirect(Long.BYTES);
  private volatile long schemaVersion = 1;
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
    if (!RelationalKey.validName(name) || result == null) {
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
      status = session.createTable(name, result);
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
    if (!RelationalKey.validName(indexName) || !RelationalKey.validName(tableName)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (activeTransactions != 0) {
      return StatusCode.RETRY;
    }
    RelationalSession session = newSession();
    if (session == null) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    TransactionOutcome outcome = new TransactionOutcome();
    StatusCode status = session.begin(IsolationLevel.SERIALIZABLE);
    if (status.isOk()) {
      status = session.resolveTable(tableName, indexedTable);
    }
    if (status.isOk() && indexedTable.hasUniqueValueIndex()) {
      status = StatusCode.CONFLICT;
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
      indexStorageTable.set(this, indexTableId, 0);
      status = session.beginScan(indexedTable, indexBuildCursor);
    }
    boolean scanActive = status.isOk();
    while (status.isOk()) {
      status = session.nextScan(indexBuildCursor, indexBuildRow);
      if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
        break;
      }
      if (status.isOk() && indexBuildRow.row().length() != Long.BYTES) {
        status = StatusCode.CORRUPTION;
      }
      if (status.isOk()) {
        catalogScratch.clear();
        status = indexBuildRow.row().copyTo(catalogScratch);
      }
      if (status.isOk()) {
        indexKeyOutput.clear();
        indexKeyOutput.putLong(0, indexBuildRow.key());
        indexKeyOutput.position(0);
        indexKeyOutput.limit(Long.BYTES);
        long value = catalogScratch.getLong(0);
        status = session.insertIndexedValue(indexStorageTable, value, indexKeyOutput);
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
          catalogOutput, indexedTable.tableId(), indexTableId, tableName);
      status = session.indexedSession().update(catalogKey.key(), catalogOutput);
    }
    if (status.isOk()) {
      status = RelationalKey.catalogTableKey(indexName, catalogKey);
    }
    if (status.isOk()) {
      CatalogRecord.encodeIndex(
          catalogOutput, indexedTable.tableId(), indexTableId, indexName);
      status = session.indexedSession().insert(catalogKey.key(), catalogOutput);
    }
    if (status.isOk()) {
      status = session.commit(outcome);
      if (status.isOk()) {
        schemaVersion++;
      }
    } else if (session.indexedSession().transaction().state() == TransactionState.ACTIVE) {
      StatusCode abort = session.abort(outcome);
      if (!abort.isOk()) {
        return abort;
      }
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

  synchronized StatusCode enterTransaction() {
    activeTransactions++;
    return StatusCode.OK;
  }

  synchronized void leaveTransaction() {
    activeTransactions--;
  }

  long schemaVersion() {
    return schemaVersion;
  }
}
