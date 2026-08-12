package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.EmbeddedDatabase;
import io.riverdb.engine.EmbeddedDatabaseOpenResult;
import io.riverdb.engine.EmbeddedSessionOpenResult;
import io.riverdb.engine.checkpoint.CheckpointResult;
import io.riverdb.engine.table.IndexedScanCursor;
import io.riverdb.engine.table.IndexedScanResult;
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
  private static final int SEQUENCE_CACHE_SLOTS = 64;
  private static final int SEQUENCE_RESERVATION_VALUES = 64;

  private final EmbeddedDatabase embedded;
  private final HeapRowResult catalogRow = new HeapRowResult();
  private final ByteBuffer catalogScratch = ByteBuffer.allocateDirect(CATALOG_ROW_BYTES);
  private final ByteBuffer catalogOutput = ByteBuffer.allocateDirect(CATALOG_ROW_BYTES);
  private final RelationalKey.LongKeyResult catalogKey = new RelationalKey.LongKeyResult();
  private final CatalogRecord.IntResult nextTableId = new CatalogRecord.IntResult();
  private final CatalogRecord.IndexResult indexRecord = new CatalogRecord.IndexResult();
  private final CatalogRecord.UserSequenceResult userSequenceRecord =
      new CatalogRecord.UserSequenceResult();
  private final TableDefinition indexedTable = new TableDefinition();
  private final TableDefinition indexStorageTable = new TableDefinition();
  private final TableDefinition updatedTable = new TableDefinition();
  private final TableDefinition referencingTable = new TableDefinition();
  private final TableSchema.ColumnName scannedTableName = new TableSchema.ColumnName();
  private final ViewDefinition scannedView = new ViewDefinition();
  private final RelationalScanCursor indexBuildCursor = new RelationalScanCursor();
  private final RelationalScanResult indexBuildRow = new RelationalScanResult();
  private final RelationalScanCursor referenceLookupCursor = new RelationalScanCursor();
  private final ValueIndexLookupResult referenceLookup = new ValueIndexLookupResult();
  private final ByteBuffer indexKeyOutput = ByteBuffer.allocateDirect(Long.BYTES);
  private final long[] cleanupIndexKeys = new long[INDEX_BUILD_BATCH_ROWS];
  private final long[] droppingIndexCatalogKeys =
      new long[TableDefinition.MAXIMUM_INDEXES];
  private final long[] sequenceCacheKeys = new long[SEQUENCE_CACHE_SLOTS];
  private final long[] sequenceCacheNextValues = new long[SEQUENCE_CACHE_SLOTS];
  private final long[] sequenceCacheIncrements = new long[SEQUENCE_CACHE_SLOTS];
  private final long[] sequenceCacheCommitSequences = new long[SEQUENCE_CACHE_SLOTS];
  private final int[] sequenceCacheRemaining = new int[SEQUENCE_CACHE_SLOTS];
  private final IndexedScanCursor catalogScanCursor = new IndexedScanCursor();
  private final IndexedScanResult catalogScanRow = new IndexedScanResult();
  private long buildLastKey;
  private boolean buildBatchFull;
  private boolean cleanupBatchComplete;
  private boolean droppingIndexAlreadyMarked;
  private boolean droppingTableAlreadyMarked;
  private int nextSequenceCacheReplacement;
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

  public static StatusCode createWithDurableWalQuorum(
      Path directory,
      Path[] followerDirectories,
      int requiredDurableNodes,
      DatabaseIncarnation database,
      WalGeneration generation,
      int maximumActiveTransactions,
      RelationalDatabaseOpenResult result) {
    if (result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    EmbeddedDatabaseOpenResult embeddedResult = new EmbeddedDatabaseOpenResult();
    StatusCode status = EmbeddedDatabase.createWithDurableWalQuorum(
        directory,
        followerDirectories,
        requiredDurableNodes,
        database,
        generation,
        maximumActiveTransactions,
        embeddedResult);
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

  public static StatusCode openWithDurableWalQuorum(
      Path directory,
      Path[] followerDirectories,
      int requiredDurableNodes,
      DatabaseIncarnation database,
      WalGeneration generation,
      int maximumActiveTransactions,
      RelationalDatabaseOpenResult result) {
    if (result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    EmbeddedDatabaseOpenResult embeddedResult = new EmbeddedDatabaseOpenResult();
    StatusCode status = EmbeddedDatabase.openWithDurableWalQuorum(
        directory,
        followerDirectories,
        requiredDurableNodes,
        database,
        generation,
        maximumActiveTransactions,
        embeddedResult);
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

  public int requiredDurableNodeCount() {
    return embedded.requiredDurableNodeCount();
  }

  public int availableDurableNodeCount() {
    return embedded.availableDurableNodeCount();
  }

  public long quorumDurableCommitSequence() {
    return embedded.quorumDurableCommitSequence();
  }

  public long replicatedWalPayloadBytes() {
    return embedded.replicatedWalPayloadBytes();
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

  public synchronized StatusCode createSequence(
      CharSequence name,
      long start,
      long increment) {
    RelationalSession session = newSession();
    if (session == null) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    TransactionOutcome outcome = new TransactionOutcome();
    StatusCode status = session.begin(IsolationLevel.SERIALIZABLE);
    if (status.isOk()) {
      status = session.createSequence(name, start, increment);
    }
    if (status.isOk()) {
      return session.commit(outcome);
    }
    if (session.indexedSession().transaction().state() == TransactionState.ACTIVE) {
      StatusCode abort = session.abort(outcome);
      if (!abort.isOk()) {
        return abort;
      }
    }
    return status;
  }

  public synchronized StatusCode dropSequence(CharSequence name) {
    RelationalSession session = newSession();
    if (session == null) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    TransactionOutcome outcome = new TransactionOutcome();
    StatusCode status = session.begin(IsolationLevel.SERIALIZABLE);
    if (status.isOk()) {
      status = session.dropSequence(name);
    }
    if (status.isOk()) {
      return session.commit(outcome);
    }
    if (session.indexedSession().transaction().state() == TransactionState.ACTIVE) {
      StatusCode abort = session.abort(outcome);
      if (!abort.isOk()) {
        return abort;
      }
    }
    return status;
  }

  public synchronized StatusCode nextSequenceValue(
      CharSequence name,
      SequenceValueResult result) {
    if (!RelationalKey.validName(name) || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    if (schemaChangeOwner != null) {
      return StatusCode.RETRY;
    }
    StatusCode status = RelationalKey.catalogTableKey(name, catalogKey);
    if (!status.isOk()) {
      return status;
    }
    return allocateSequence(
        catalogKey.key(), name, 0, Long.MIN_VALUE, Long.MAX_VALUE, result);
  }

  public synchronized StatusCode nextIdentityValue(
      TableDefinition table,
      SequenceValueResult result) {
    if (table == null
        || result == null
        || !table.isOwnedBy(this)
        || !table.hasIdentity()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    return allocateSequence(
        RelationalKey.identitySequenceKey(table.tableId()),
        null,
        table.tableId(),
        1,
        RelationalKey.MAXIMUM_USER_KEY,
        result);
  }

  private StatusCode allocateSequence(
      long sequenceKey,
      CharSequence name,
      int identityTableId,
      long minimum,
      long maximum,
      SequenceValueResult result) {
    if (schemaChangeOwner != null) {
      return StatusCode.RETRY;
    }
    int cachedSlot = sequenceCacheSlot(sequenceKey);
    if (cachedSlot >= 0) {
      long value = sequenceCacheNextValues[cachedSlot];
      sequenceCacheRemaining[cachedSlot]--;
      if (sequenceCacheRemaining[cachedSlot] > 0) {
        sequenceCacheNextValues[cachedSlot] =
            value + sequenceCacheIncrements[cachedSlot];
      }
      result.set(value, sequenceCacheCommitSequences[cachedSlot]);
      return StatusCode.OK;
    }
    RelationalSession session = newSession();
    if (session == null) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    TransactionOutcome outcome = new TransactionOutcome();
    StatusCode status = session.begin(IsolationLevel.SERIALIZABLE);
    if (status.isOk()) {
      status = session.indexedSession().fetchByKey(sequenceKey, catalogRow);
    }
    if (status.isOk()) {
      status = identityTableId > 0
          ? CatalogRecord.decodeIdentitySequence(
              catalogRow, catalogScratch, identityTableId, userSequenceRecord)
          : CatalogRecord.decodeUserSequence(
              catalogRow, catalogScratch, name, userSequenceRecord);
    }
    if (status.isOk() && userSequenceRecord.isExhausted()) {
      status = StatusCode.RESOURCE_EXHAUSTED;
    }
    long value = status.isOk() ? userSequenceRecord.nextValue() : 0;
    long increment = status.isOk() ? userSequenceRecord.increment() : 0;
    long next = value;
    int reserved = 0;
    boolean exhausted = false;
    while (status.isOk()
        && reserved < SEQUENCE_RESERVATION_VALUES
        && !exhausted) {
      reserved++;
      if (additionOverflows(next, increment)) {
        exhausted = true;
      } else {
        long candidate = next + increment;
        if (candidate < minimum || candidate > maximum) {
          exhausted = true;
        } else {
          next = candidate;
        }
      }
    }
    if (status.isOk()) {
      if (identityTableId > 0) {
        CatalogRecord.encodeIdentitySequence(
            catalogOutput, identityTableId, next, exhausted);
      } else {
        CatalogRecord.encodeUserSequence(
            catalogOutput, name, next, increment, exhausted);
      }
      status = session.indexedSession().update(sequenceKey, catalogOutput);
    }
    if (status.isOk()) {
      status = session.commit(outcome);
    } else if (session.indexedSession().transaction().state() == TransactionState.ACTIVE) {
      StatusCode abort = session.abort(outcome);
      if (!abort.isOk()) {
        return abort;
      }
    }
    if (status.isOk()) {
      result.set(value, outcome.commitSequence());
      if (reserved > 1) {
        int slot = writableSequenceCacheSlot(sequenceKey);
        sequenceCacheKeys[slot] = sequenceKey;
        sequenceCacheNextValues[slot] = value + increment;
        sequenceCacheIncrements[slot] = increment;
        sequenceCacheCommitSequences[slot] = outcome.commitSequence();
        sequenceCacheRemaining[slot] = reserved - 1;
      }
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

  public synchronized StatusCode dropTable(CharSequence name) {
    return dropTable(name, Integer.MAX_VALUE);
  }

  public synchronized StatusCode renameTable(
      CharSequence currentName,
      CharSequence renamedName) {
    RelationalSession session = newSession();
    if (session == null) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    TransactionOutcome outcome = new TransactionOutcome();
    StatusCode status = session.begin(IsolationLevel.SERIALIZABLE);
    if (status.isOk()) {
      status = session.renameTable(currentName, renamedName);
    }
    if (status.isOk()) {
      return session.commit(outcome);
    }
    if (session.indexedSession().transaction().state() == TransactionState.ACTIVE) {
      StatusCode abort = session.abort(outcome);
      if (!abort.isOk()) {
        return abort;
      }
    }
    return status;
  }

  public synchronized StatusCode renameColumn(
      CharSequence tableName,
      CharSequence currentName,
      CharSequence renamedName) {
    RelationalSession session = newSession();
    if (session == null) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    TransactionOutcome outcome = new TransactionOutcome();
    StatusCode status = session.begin(IsolationLevel.SERIALIZABLE);
    if (status.isOk()) {
      status = session.renameColumn(tableName, currentName, renamedName);
    }
    if (status.isOk()) {
      return session.commit(outcome);
    }
    if (session.indexedSession().transaction().state() == TransactionState.ACTIVE) {
      StatusCode abort = session.abort(outcome);
      if (!abort.isOk()) {
        return abort;
      }
    }
    return status;
  }

  public synchronized StatusCode renameIndex(
      CharSequence currentName,
      CharSequence renamedName) {
    RelationalSession session = newSession();
    if (session == null) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    TransactionOutcome outcome = new TransactionOutcome();
    StatusCode status = session.begin(IsolationLevel.SERIALIZABLE);
    if (status.isOk()) {
      status = session.renameIndex(currentName, renamedName);
    }
    if (status.isOk()) {
      return session.commit(outcome);
    }
    if (session.indexedSession().transaction().state() == TransactionState.ACTIVE) {
      StatusCode abort = session.abort(outcome);
      if (!abort.isOk()) {
        return abort;
      }
    }
    return status;
  }

  synchronized StatusCode renameTable(
      RelationalSession session,
      CharSequence currentName,
      CharSequence renamedName) {
    StatusCode status = session.resolveTable(currentName, indexedTable);
    if (status.isOk()) {
      status = checkViewReferences(session, indexedTable.tableId());
    }
    if (status.isOk()) {
      status = RelationalKey.catalogTableKey(renamedName, catalogKey);
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
      CatalogRecord.encodeTable(
          catalogOutput,
          indexedTable.tableId(),
          0,
          TableDefinition.INDEX_NONE,
          -1,
          renamedName,
          indexedTable);
      status = session.indexedSession().insert(catalogKey.key(), catalogOutput);
    }
    if (status.isOk()) {
      status = RelationalKey.catalogTableKey(currentName, catalogKey);
    }
    return status.isOk()
        ? session.indexedSession().delete(catalogKey.key()) : status;
  }

  synchronized StatusCode renameColumn(
      RelationalSession session,
      CharSequence tableName,
      CharSequence currentName,
      CharSequence renamedName) {
    StatusCode status = session.resolveTable(tableName, indexedTable);
    if (status.isOk()) {
      status = checkViewReferences(session, indexedTable.tableId());
    }
    if (status.isOk()) {
      updatedTable.set(
          this,
          indexedTable.tableId(),
          0,
          TableDefinition.INDEX_NONE,
          -1,
          indexedTable);
      status = updatedTable.renameColumn(currentName, renamedName);
    }
    if (status.isOk()) {
      status = RelationalKey.catalogTableKey(tableName, catalogKey);
    }
    if (status.isOk()) {
      CatalogRecord.encodeTable(
          catalogOutput,
          updatedTable.tableId(),
          0,
          TableDefinition.INDEX_NONE,
          -1,
          tableName,
          updatedTable);
      status = session.indexedSession().update(catalogKey.key(), catalogOutput);
    }
    return status;
  }

  synchronized StatusCode renameIndex(
      RelationalSession session,
      CharSequence currentName,
      CharSequence renamedName) {
    StatusCode status = RelationalKey.catalogTableKey(currentName, catalogKey);
    if (status.isOk()) {
      status = session.indexedSession().fetchByKey(catalogKey.key(), catalogRow);
    }
    if (status.isOk()) {
      status = CatalogRecord.decodeIndex(
          catalogRow, catalogScratch, currentName, indexRecord);
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
      status = session.indexedSession().fetchByKey(catalogKey.key(), catalogRow);
      if (status.isOk()) {
        status = StatusCode.CONFLICT;
      } else if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
      }
    }
    if (status.isOk()) {
      CatalogRecord.encodeIndex(
          catalogOutput,
          indexRecord.tableId(),
          indexRecord.indexTableId(),
          indexRecord.state(),
          renamedName,
          indexRecord.isUnique());
      status = session.indexedSession().insert(catalogKey.key(), catalogOutput);
    }
    if (status.isOk()) {
      status = RelationalKey.catalogTableKey(currentName, catalogKey);
    }
    return status.isOk()
        ? session.indexedSession().delete(catalogKey.key()) : status;
  }

  synchronized StatusCode dropTable(
      CharSequence name,
      int maximumCleanupBatches) {
    if (!RelationalKey.validName(name) || maximumCleanupBatches <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    RelationalSession session = newSession();
    if (session == null) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    TransactionOutcome outcome = new TransactionOutcome();
    StatusCode status = session.begin(IsolationLevel.SERIALIZABLE);
    if (status.isOk()) {
      status = session.beginPersistentSchemaChange();
    }
    if (status.isOk()) {
      status = markDroppingTable(session, name);
    }
    if (session.indexedSession().transaction().state() == TransactionState.ACTIVE) {
      StatusCode terminal = status.isOk() && !droppingTableAlreadyMarked
          ? session.commitBuildPhase(outcome) : session.abortBuildPhase(outcome);
      if (status.isOk()) {
        status = terminal;
      }
    }
    if (status.isOk() && !droppingTableAlreadyMarked) {
      status = publishDroppingTableSchema(session);
    }
    if (!status.isOk()) {
      session.releasePersistentSchemaChange();
      return status;
    }
    return cleanupDroppingTable(
        session, name, outcome, maximumCleanupBatches);
  }

  synchronized StatusCode markDroppingTable(
      RelationalSession session,
      CharSequence name) {
    droppingTableAlreadyMarked = false;
    StatusCode status = RelationalKey.catalogTableKey(name, catalogKey);
    if (status.isOk()) {
      status = session.indexedSession().fetchByKey(catalogKey.key(), catalogRow);
    }
    if (status.isOk()) {
      droppingTableAlreadyMarked =
          CatalogRecord.isDroppingTable(catalogRow, catalogScratch);
      status = droppingTableAlreadyMarked
          ? CatalogRecord.decodeDroppingTable(
              catalogRow, catalogScratch, name, this, indexedTable)
          : CatalogRecord.decodeTable(
              catalogRow, catalogScratch, name, this, indexedTable);
    }
    if (status.isOk() && !droppingTableAlreadyMarked) {
      status = checkTableReferences(session, indexedTable, 0, false);
    }
    if (status.isOk() && !droppingTableAlreadyMarked) {
      CatalogRecord.encodeDroppingTable(
          catalogOutput, indexedTable.tableId(), name, indexedTable);
      status = session.indexedSession().update(catalogKey.key(), catalogOutput);
      if (status.isOk() && indexedTable.hasIdentity()) {
        status = session.indexedSession().delete(
            RelationalKey.identitySequenceKey(indexedTable.tableId()));
      }
    }
    return status;
  }

  synchronized StatusCode checkDeleteReferences(
      RelationalSession session,
      TableDefinition table,
      long key) {
    if (session == null
        || table == null
        || !table.isOwnedBy(this)
        || key < 0
        || key > RelationalKey.MAXIMUM_USER_KEY) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return checkTableReferences(session, table, key, true);
  }

  private StatusCode checkTableReferences(
      RelationalSession session,
      TableDefinition referencedTable,
      long key,
      boolean checkRows) {
    StatusCode status = session.indexedSession().beginScan(
        Long.MIN_VALUE, 0, catalogScanCursor);
    boolean scanActive = status.isOk();
    while (status.isOk()) {
      status = session.indexedSession().nextScan(catalogScanCursor, catalogScanRow);
      if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
        break;
      }
      StatusCode decoded = status.isOk()
          ? CatalogRecord.decodeTableForScan(
              catalogScanRow.row(),
              catalogScratch,
              this,
              scannedTableName,
              referencingTable)
          : status;
      if (decoded == StatusCode.CONFLICT) {
        if (!checkRows) {
          StatusCode viewDecoded = CatalogRecord.decodeViewForScan(
              catalogScanRow.row(),
              catalogScratch,
              scannedTableName,
              scannedView);
          if (viewDecoded.isOk()
              && scannedView.baseTableId() == referencedTable.tableId()) {
            status = StatusCode.CONFLICT;
            break;
          }
          if (viewDecoded != StatusCode.CONFLICT && !viewDecoded.isOk()) {
            status = viewDecoded;
            break;
          }
        }
        continue;
      }
      if (!decoded.isOk()) {
        status = decoded;
        break;
      }
      if (!referencingTable.referencesTable(referencedTable.tableId())) {
        continue;
      }
      if (!checkRows) {
        status = StatusCode.FOREIGN_KEY_VIOLATION;
        break;
      }
      for (int column = 1;
          status.isOk() && column < referencingTable.columnCount();
          column++) {
        if (!referencingTable.hasReference(column)
            || referencingTable.referenceTableId(column) != referencedTable.tableId()) {
          continue;
        }
        status = referenceExists(session, referencingTable, column, key);
      }
    }
    if (scanActive) {
      StatusCode close = session.indexedSession().closeScan(catalogScanCursor);
      catalogScanCursor.reset();
      if (status.isOk()) {
        status = close;
      }
    }
    return status;
  }

  private StatusCode checkViewReferences(
      RelationalSession session,
      int tableId) {
    StatusCode status = session.indexedSession().beginScan(
        Long.MIN_VALUE, 0, catalogScanCursor);
    boolean scanActive = status.isOk();
    while (status.isOk()) {
      status = session.indexedSession().nextScan(
          catalogScanCursor, catalogScanRow);
      if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
        break;
      }
      StatusCode decoded = status.isOk()
          ? CatalogRecord.decodeViewForScan(
              catalogScanRow.row(),
              catalogScratch,
              scannedTableName,
              scannedView)
          : status;
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
    if (scanActive) {
      StatusCode close = session.indexedSession().closeScan(catalogScanCursor);
      catalogScanCursor.reset();
      if (status.isOk()) {
        status = close;
      }
    }
    return status;
  }

  private StatusCode referenceExists(
      RelationalSession session,
      TableDefinition child,
      int column,
      long key) {
    if (!child.hasIndexOn(column)) {
      return StatusCode.CORRUPTION;
    }
    StatusCode status;
    if (child.hasUniqueIndexOn(column)) {
      status = session.fetchByUniqueValue(child, column, key, referenceLookup);
    } else {
      status = session.beginNonUniqueValueLookup(
          child, column, key, referenceLookupCursor);
      if (status.isOk()) {
        status = session.nextNonUniqueValueLookup(
            child, referenceLookupCursor, referenceLookup);
        StatusCode close = session.closeScan(referenceLookupCursor);
        referenceLookupCursor.reset();
        if (status.isOk() && !close.isOk()) {
          status = close;
        }
      }
    }
    if (status.isOk()) {
      return StatusCode.FOREIGN_KEY_VIOLATION;
    }
    return status == StatusCode.CONFLICT || status == StatusCode.INVALID_EXTERNAL_INPUT
        ? StatusCode.OK : status;
  }

  StatusCode finishDroppingTable(
      RelationalSession session,
      CharSequence tableName,
      TransactionOutcome outcome) {
    StatusCode status = publishDroppingTableSchema(session);
    return status.isOk()
        ? cleanupDroppingTable(
            session, tableName, outcome, Integer.MAX_VALUE)
        : status;
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
    if (!RelationalKey.validName(indexName)
        || !RelationalKey.validName(tableName)
        || maximumCleanupBatches <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    RelationalSession session = newSession();
    if (session == null) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    TransactionOutcome outcome = new TransactionOutcome();
    StatusCode status = session.begin(IsolationLevel.SERIALIZABLE);
    if (status.isOk()) {
      status = session.beginPersistentSchemaChange();
    }
    if (status.isOk()) {
      status = markDroppingValueIndex(session, indexName, tableName);
    }
    if (session.indexedSession().transaction().state() == TransactionState.ACTIVE) {
      StatusCode terminal = status.isOk() && !droppingIndexAlreadyMarked
          ? session.commitBuildPhase(outcome) : session.abortBuildPhase(outcome);
      if (status.isOk()) {
        status = terminal;
      }
    }
    if (status.isOk() && !droppingIndexAlreadyMarked) {
      status = publishDroppingSchema(session);
    }
    if (!status.isOk()) {
      session.releasePersistentSchemaChange();
      return status;
    }
    return cleanupUniqueValueIndex(
        session, indexName, tableName, outcome, maximumCleanupBatches);
  }

  StatusCode markDroppingValueIndex(
      RelationalSession session,
      CharSequence indexName,
      CharSequence tableName) {
    droppingIndexAlreadyMarked = false;
    StatusCode status = session.resolveTable(tableName, indexedTable);
    if (status.isOk()) {
      status = RelationalKey.catalogTableKey(indexName, catalogKey);
    }
    if (status.isOk()) {
      status = session.indexedSession().fetchByKey(catalogKey.key(), catalogRow);
    }
    if (status.isOk()) {
      status = CatalogRecord.decodeIndex(
          catalogRow, catalogScratch, indexName, indexRecord);
    }
    int indexTableId = status.isOk() ? indexRecord.indexTableId() : 0;
    int indexSlot = -1;
    for (int slot = 0; status.isOk() && slot < indexedTable.uniqueIndexCount(); slot++) {
      if (indexedTable.uniqueIndexTableId(slot) == indexTableId) {
        indexSlot = slot;
        break;
      }
    }
    if (status.isOk()
        && (indexRecord.tableId() != indexedTable.tableId() || indexSlot < 0)) {
      status = StatusCode.CONFLICT;
    }
    if (status.isOk()
        && indexRecord.state() != indexedTable.uniqueIndexState(indexSlot)) {
      status = StatusCode.CORRUPTION;
    }
    if (status.isOk()
        && (indexRecord.isUnique() != indexedTable.indexIsUnique(indexSlot)
            || indexRecord.isConstraint()
                != indexedTable.indexIsConstraint(indexSlot))) {
      status = StatusCode.CORRUPTION;
    }
    if (status.isOk() && indexRecord.isConstraint()) {
      status = StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (status.isOk()) {
      indexStorageTable.set(this, indexTableId, 0, TableDefinition.INDEX_NONE);
      droppingIndexAlreadyMarked =
          indexRecord.state() == TableDefinition.INDEX_DROPPING;
    }
    if (status.isOk() && !droppingIndexAlreadyMarked) {
      status = RelationalKey.catalogTableKey(tableName, catalogKey);
    }
    if (status.isOk() && !droppingIndexAlreadyMarked) {
      CatalogRecord.encodeTable(
          catalogOutput,
          indexedTable.tableId(),
          indexTableId,
          TableDefinition.INDEX_DROPPING,
          indexedTable.uniqueIndexColumn(indexSlot),
          tableName,
          indexedTable,
          indexedTable.indexIsUnique(indexSlot));
      status = session.indexedSession().update(catalogKey.key(), catalogOutput);
    }
    if (status.isOk() && !droppingIndexAlreadyMarked) {
      status = RelationalKey.catalogTableKey(indexName, catalogKey);
    }
    if (status.isOk() && !droppingIndexAlreadyMarked) {
      CatalogRecord.encodeIndex(
          catalogOutput,
          indexedTable.tableId(),
          indexTableId,
          TableDefinition.INDEX_DROPPING,
          indexName,
          indexedTable.indexIsUnique(indexSlot));
      status = session.indexedSession().update(catalogKey.key(), catalogOutput);
    }
    return status;
  }

  StatusCode finishDroppingValueIndex(
      RelationalSession session,
      CharSequence indexName,
      CharSequence tableName,
      TransactionOutcome outcome) {
    StatusCode status = publishDroppingSchema(session);
    return status.isOk()
        ? cleanupUniqueValueIndex(
            session, indexName, tableName, outcome, Integer.MAX_VALUE)
        : status;
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
          session, indexName, tableName, outcome, Integer.MAX_VALUE);
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
          && (indexBuildRow.row().length() < indexedTable.fixedRowBytes()
              || indexBuildRow.row().length() > indexedTable.maximumRowBytes())) {
        status = StatusCode.CORRUPTION;
      }
      if (status.isOk()) {
        catalogScratch.clear();
        status = indexBuildRow.row().copyTo(catalogScratch);
        if (status.isOk()) {
          catalogScratch.flip();
          status = indexedTable.isValidRow(catalogScratch)
              ? StatusCode.OK : StatusCode.CORRUPTION;
        }
      }
      boolean nullValue = status.isOk()
          && (catalogScratch.getLong(indexedTable.nullMaskOffset())
              & 1L << indexedTable.uniqueValueIndexColumn()) != 0;
      if (status.isOk() && !nullValue) {
        int buildingSlot = indexedTable.buildingIndexSlot();
        int column = indexedTable.uniqueValueIndexColumn();
        long value = indexedTable.isVarchar(column) ? 0 : catalogScratch.getLong(
            (column - 1) * Long.BYTES);
        status = indexedTable.isVarchar(column)
            ? session.ensureTextIndexedValue(
                indexStorageTable,
                indexedTable,
                column,
                catalogScratch,
                indexBuildRow.key(),
                indexedTable.indexIsUnique(buildingSlot))
            : indexedTable.indexIsUnique(buildingSlot)
            ? session.ensureIndexedValue(
                indexStorageTable, value, indexBuildRow.key())
            : session.ensureNonUniqueIndexedValue(
                indexStorageTable, value, indexBuildRow.key());
        if (status == StatusCode.CONFLICT
            && indexedTable.indexIsConstraint(buildingSlot)) {
          status = StatusCode.UNIQUE_VIOLATION;
        }
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
      TransactionOutcome outcome,
      int maximumCleanupBatches) {
    StatusCode status = StatusCode.OK;
    boolean complete = false;
    int batches = 0;
    while (status.isOk() && !complete && batches < maximumCleanupBatches) {
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
      batches++;
    }
    if (status.isOk() && !complete) {
      session.releasePersistentSchemaChange();
      return StatusCode.RETRY;
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

  private StatusCode cleanupDroppingTable(
      RelationalSession session,
      CharSequence tableName,
      TransactionOutcome outcome,
      int maximumCleanupBatches) {
    StatusCode status = StatusCode.OK;
    int batches = 0;
    for (int slot = 0;
        status.isOk() && slot < indexedTable.uniqueIndexCount();
        slot++) {
      boolean complete = false;
      while (status.isOk() && !complete && batches < maximumCleanupBatches) {
        status = cleanupPhysicalTableBatch(
            session, indexedTable.uniqueIndexTableId(slot), outcome);
        complete = status.isOk() && cleanupBatchComplete;
        batches++;
      }
      if (status.isOk() && !complete) {
        session.releasePersistentSchemaChange();
        return StatusCode.RETRY;
      }
    }
    boolean tableComplete = false;
    while (status.isOk()
        && !tableComplete
        && batches < maximumCleanupBatches) {
      status = cleanupPhysicalTableBatch(
          session, indexedTable.tableId(), outcome);
      tableComplete = status.isOk() && cleanupBatchComplete;
      batches++;
    }
    if (status.isOk() && !tableComplete) {
      session.releasePersistentSchemaChange();
      return StatusCode.RETRY;
    }
    if (status.isOk()) {
      status = removeDroppingTableCatalog(session, tableName, outcome);
    }
    if (!status.isOk()) {
      session.releasePersistentSchemaChange();
    }
    return status;
  }

  private StatusCode cleanupPhysicalTableBatch(
      RelationalSession session,
      int tableId,
      TransactionOutcome outcome) {
    indexStorageTable.set(this, tableId, 0, TableDefinition.INDEX_NONE);
    cleanupBatchComplete = false;
    StatusCode status = session.begin(IsolationLevel.REPEATABLE_READ);
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
    cleanupBatchComplete = status.isOk() && exhausted;
    return status;
  }

  private StatusCode removeDroppingTableCatalog(
      RelationalSession session,
      CharSequence tableName,
      TransactionOutcome outcome) {
    StatusCode status = session.begin(IsolationLevel.SERIALIZABLE);
    boolean scanActive = false;
    if (status.isOk()) {
      status = session.indexedSession().beginScan(
          Long.MIN_VALUE, 0, catalogScanCursor);
      scanActive = status.isOk();
    }
    int indexCatalogCount = 0;
    while (status.isOk()) {
      status = session.indexedSession().nextScan(
          catalogScanCursor, catalogScanRow);
      if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
        break;
      }
      StatusCode decoded = status.isOk()
          ? CatalogRecord.decodeIndexForTable(
              catalogScanRow.row(),
              catalogScratch,
              indexedTable.tableId(),
              indexRecord)
          : status;
      if (decoded == StatusCode.CONFLICT) {
        continue;
      }
      if (!decoded.isOk()) {
        status = decoded;
      } else if (indexCatalogCount >= droppingIndexCatalogKeys.length) {
        status = StatusCode.CORRUPTION;
      } else {
        droppingIndexCatalogKeys[indexCatalogCount++] = catalogScanRow.key();
      }
    }
    if (scanActive) {
      StatusCode close = session.indexedSession().closeScan(catalogScanCursor);
      if (status.isOk()) {
        status = close;
      }
    }
    catalogScanCursor.reset();
    for (int index = 0; status.isOk() && index < indexCatalogCount; index++) {
      status = session.indexedSession().delete(droppingIndexCatalogKeys[index]);
      droppingIndexCatalogKeys[index] = 0;
    }
    if (status.isOk()) {
      status = RelationalKey.catalogTableKey(tableName, catalogKey);
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
    }
    return status;
  }

  StatusCode buildUniqueValueIndex(
      RelationalSession session,
      CharSequence indexName,
      CharSequence tableName,
      CharSequence columnName) {
    return buildValueIndex(session, indexName, tableName, columnName, true, false);
  }

  StatusCode buildValueIndex(
      RelationalSession session,
      CharSequence indexName,
      CharSequence tableName,
      CharSequence columnName,
      boolean unique,
      boolean constraint) {
    StatusCode status = performValueIndexBuild(
        session, indexName, tableName, columnName, unique, constraint);
    indexBuildCursor.reset();
    return status;
  }

  private StatusCode performValueIndexBuild(
      RelationalSession session,
      CharSequence indexName,
      CharSequence tableName,
      CharSequence columnName,
      boolean unique,
      boolean constraint) {
    StatusCode status = session.resolveTable(tableName, indexedTable);
    if (!status.isOk()) {
      return status;
    }
    int indexColumn = indexedTable.findColumn(columnName);
    status = validateValueIndexBuild(indexColumn);
    if (!status.isOk()) {
      return status;
    }
    status = loadNextValueIndexTableId(session, indexName);
    if (!status.isOk()) {
      return status;
    }
    int indexTableId = nextTableId.value();
    if (indexTableId > RelationalKey.MAXIMUM_TABLE_ID) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    status = populateValueIndex(
        session, indexTableId, indexColumn, unique, constraint);
    if (!status.isOk()) {
      return status;
    }
    return publishValueIndex(
        session,
        indexName,
        tableName,
        indexColumn,
        indexTableId,
        unique,
        constraint);
  }

  private StatusCode validateValueIndexBuild(int indexColumn) {
    if (indexColumn <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (indexedTable.hasIndexOn(indexColumn)) {
      return StatusCode.CONFLICT;
    }
    if (indexedTable.hasBuildingUniqueValueIndex()) {
      return StatusCode.RETRY;
    }
    if (indexedTable.uniqueIndexCount() >= TableDefinition.MAXIMUM_INDEXES) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    return StatusCode.OK;
  }

  private StatusCode loadNextValueIndexTableId(
      RelationalSession session, CharSequence indexName) {
    StatusCode status = RelationalKey.catalogTableKey(indexName, catalogKey);
    if (!status.isOk()) {
      return status;
    }
    status = session.indexedSession().fetchByKey(catalogKey.key(), catalogRow);
    if (status.isOk()) {
      return StatusCode.CONFLICT;
    }
    if (status != StatusCode.CONFLICT) {
      return status;
    }
    status = session.indexedSession().fetchByKey(
        RelationalKey.CATALOG_SEQUENCE_KEY, catalogRow);
    return status.isOk()
        ? CatalogRecord.decodeSequence(catalogRow, catalogScratch, nextTableId)
        : status;
  }

  private StatusCode populateValueIndex(
      RelationalSession session,
      int indexTableId,
      int indexColumn,
      boolean unique,
      boolean constraint) {
    indexStorageTable.set(
        this, indexTableId, 0, TableDefinition.INDEX_NONE);
    StatusCode status = session.beginScan(indexedTable, indexBuildCursor);
    if (!status.isOk()) {
      return status;
    }
    status = scanValueIndexRows(session, indexColumn, unique, constraint);
    StatusCode close = session.closeScan(indexBuildCursor);
    if (status.isOk()) {
      status = close;
    }
    return status;
  }

  private StatusCode scanValueIndexRows(
      RelationalSession session,
      int indexColumn,
      boolean unique,
      boolean constraint) {
    while (true) {
      StatusCode status = session.nextScan(indexBuildCursor, indexBuildRow);
      if (status == StatusCode.CONFLICT) {
        return StatusCode.OK;
      }
      if (!status.isOk()) {
        return status;
      }
      status = copyAndValidateIndexBuildRow();
      if (!status.isOk()) {
        return status;
      }
      status = insertValueIndexEntry(
          session, indexColumn, unique, constraint);
      if (!status.isOk()) {
        return status;
      }
    }
  }

  private StatusCode copyAndValidateIndexBuildRow() {
    int rowBytes = indexBuildRow.row().length();
    if (rowBytes < indexedTable.fixedRowBytes()
        || rowBytes > indexedTable.maximumRowBytes()) {
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

  private StatusCode insertValueIndexEntry(
      RelationalSession session,
      int indexColumn,
      boolean unique,
      boolean constraint) {
    boolean nullValue = (catalogScratch.getLong(indexedTable.nullMaskOffset())
        & 1L << indexColumn) != 0;
    if (nullValue) {
      return StatusCode.OK;
    }
    if (indexedTable.isVarchar(indexColumn)) {
      StatusCode status = session.ensureTextIndexedValue(
          indexStorageTable,
          indexedTable,
          indexColumn,
          catalogScratch,
          indexBuildRow.key(),
          unique);
      return indexConstraintStatus(status, constraint);
    }
    long value = catalogScratch.getLong((indexColumn - 1) * Long.BYTES);
    if (!unique) {
      return session.insertNonUniqueIndexedValue(
          indexStorageTable, value, indexBuildRow.key());
    }
    indexKeyOutput.clear();
    indexKeyOutput.putLong(0, indexBuildRow.key());
    indexKeyOutput.position(0);
    indexKeyOutput.limit(Long.BYTES);
    StatusCode status = session.insertIndexedValue(
        indexStorageTable, value, indexKeyOutput);
    return indexConstraintStatus(status, constraint);
  }

  private static StatusCode indexConstraintStatus(
      StatusCode status, boolean constraint) {
    return status == StatusCode.CONFLICT && constraint
        ? StatusCode.UNIQUE_VIOLATION : status;
  }

  private StatusCode publishValueIndex(
      RelationalSession session,
      CharSequence indexName,
      CharSequence tableName,
      int indexColumn,
      int indexTableId,
      boolean unique,
      boolean constraint) {
    CatalogRecord.encodeSequence(catalogOutput, indexTableId + 1);
    StatusCode status = session.indexedSession().update(
        RelationalKey.CATALOG_SEQUENCE_KEY, catalogOutput);
    if (!status.isOk()) {
      return status;
    }
    status = RelationalKey.catalogTableKey(tableName, catalogKey);
    if (!status.isOk()) {
      return status;
    }
    CatalogRecord.encodeTable(
        catalogOutput,
        indexedTable.tableId(),
        indexTableId,
        TableDefinition.INDEX_READY,
        indexColumn,
        tableName,
        indexedTable,
        unique,
        constraint);
    status = session.indexedSession().update(catalogKey.key(), catalogOutput);
    if (!status.isOk()) {
      return status;
    }
    status = RelationalKey.catalogTableKey(indexName, catalogKey);
    if (!status.isOk()) {
      return status;
    }
    CatalogRecord.encodeIndex(
        catalogOutput,
        indexedTable.tableId(),
        indexTableId,
        TableDefinition.INDEX_READY,
        indexName,
        unique,
        constraint);
    return session.indexedSession().insert(catalogKey.key(), catalogOutput);
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

  private static boolean additionOverflows(long value, long increment) {
    return increment > 0 && value > Long.MAX_VALUE - increment
        || increment < 0 && value < Long.MIN_VALUE - increment;
  }

  private int sequenceCacheSlot(long sequenceKey) {
    for (int slot = 0; slot < SEQUENCE_CACHE_SLOTS; slot++) {
      if (sequenceCacheRemaining[slot] > 0 && sequenceCacheKeys[slot] == sequenceKey) {
        return slot;
      }
    }
    return -1;
  }

  private int writableSequenceCacheSlot(long sequenceKey) {
    for (int slot = 0; slot < SEQUENCE_CACHE_SLOTS; slot++) {
      if (sequenceCacheKeys[slot] == sequenceKey || sequenceCacheRemaining[slot] == 0) {
        return slot;
      }
    }
    int slot = nextSequenceCacheReplacement;
    nextSequenceCacheReplacement = (slot + 1) % SEQUENCE_CACHE_SLOTS;
    return slot;
  }

  private void clearSequenceCache() {
    for (int slot = 0; slot < SEQUENCE_CACHE_SLOTS; slot++) {
      sequenceCacheRemaining[slot] = 0;
    }
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
        clearSequenceCache();
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

  private synchronized StatusCode publishDroppingSchema(RelationalSession owner) {
    if (schemaChangeOwner != owner) {
      return StatusCode.NOT_OWNER;
    }
    int indexTableId = indexStorageTable.tableId();
    schemaVersion++;
    indexStorageTable.set(
        this, indexTableId, 0, TableDefinition.INDEX_NONE);
    return StatusCode.OK;
  }

  private synchronized StatusCode publishDroppingTableSchema(
      RelationalSession owner) {
    if (schemaChangeOwner != owner) {
      return StatusCode.NOT_OWNER;
    }
    schemaVersion++;
    return StatusCode.OK;
  }

  long schemaVersion() {
    return schemaVersion;
  }
}
