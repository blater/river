package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.engine.table.IndexedSavepoint;
import io.riverdb.storage.heap.HeapRowResult;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import io.riverdb.tx.api.TransactionState;
import java.nio.ByteBuffer;

/** Transaction session over catalog-resolved logical tables in one physical keyspace. */
public final class RelationalSession {
  private static final long INDEX_VALUE_BIAS = 1L << 47;
  private static final long MINIMUM_INDEXED_VALUE = -INDEX_VALUE_BIAS;
  private static final long MAXIMUM_INDEXED_VALUE = INDEX_VALUE_BIAS - 2;
  private static final long MAXIMUM_INDEXED_VALUE_EXCLUSIVE = INDEX_VALUE_BIAS - 1;
  private static final long NON_UNIQUE_VALUE_BIAS = 1L << 46;
  private static final long MINIMUM_NON_UNIQUE_VALUE = -NON_UNIQUE_VALUE_BIAS;
  private static final long MAXIMUM_NON_UNIQUE_VALUE = NON_UNIQUE_VALUE_BIAS - 2;
  private static final long MAXIMUM_NON_UNIQUE_VALUE_EXCLUSIVE =
      NON_UNIQUE_VALUE_BIAS - 1;
  private static final long NON_UNIQUE_ENTRY_FLAG = 1L << 47;
  private static final long NON_UNIQUE_ALLOCATOR_KEY = NON_UNIQUE_ENTRY_FLAG - 1;
  private static final int NON_UNIQUE_ENTRY_BYTES = 3 * Long.BYTES;
  private static final int MAXIMUM_DUPLICATE_CHAIN = 64 * 1024;

  private final RelationalDatabase database;
  private final IndexedTransactionSession session;
  private final RelationalKey.LongKeyResult physicalKey = new RelationalKey.LongKeyResult();
  private final HeapRowResult catalogRow = new HeapRowResult();
  private final ByteBuffer catalogScratch = ByteBuffer.allocateDirect(CatalogRecord.MAXIMUM_BYTES);
  private final ByteBuffer catalogOutput = ByteBuffer.allocateDirect(CatalogRecord.MAXIMUM_BYTES);
  private final CatalogRecord.IntResult nextTableId = new CatalogRecord.IntResult();
  private final TableDefinition valueIndexTable = new TableDefinition();
  private final TableSchema schemaScratch = new TableSchema();
  private final HeapRowResult indexedKeyRow = new HeapRowResult();
  private final HeapRowResult indexHeadRow = new HeapRowResult();
  private final HeapRowResult indexEntryRow = new HeapRowResult();
  private final HeapRowResult indexAllocatorRow = new HeapRowResult();
  private final ByteBuffer valueScratch = ByteBuffer.allocateDirect(Long.BYTES);
  private final ByteBuffer indexEntryScratch = ByteBuffer.allocateDirect(NON_UNIQUE_ENTRY_BYTES);
  private final ByteBuffer rowScratch = ByteBuffer.allocateDirect(
      (TableSchema.MAXIMUM_COLUMNS - 1) * Long.BYTES);
  private final ByteBuffer indexRow = ByteBuffer.allocateDirect(Long.BYTES);
  private final ByteBuffer nonUniqueIndexRow = ByteBuffer.allocateDirect(NON_UNIQUE_ENTRY_BYTES);
  private final long[] previousIndexedValues = new long[TableDefinition.MAXIMUM_INDEXES];
  private boolean registeredTransaction;
  private boolean schemaChangeActive;
  private int schemaChangeMutationStart;

  RelationalSession(RelationalDatabase owner, IndexedTransactionSession indexedSession) {
    database = owner;
    session = indexedSession;
  }

  public StatusCode begin(IsolationLevel isolationLevel) {
    if (registeredTransaction) {
      return StatusCode.CONFLICT;
    }
    StatusCode status = database.enterTransaction(this);
    if (status.isOk()) {
      status = session.begin(isolationLevel);
    }
    if (status.isOk()) {
      registeredTransaction = true;
    } else {
      database.leaveTransaction();
    }
    return status;
  }

  public StatusCode resolveTable(CharSequence name, TableDefinition result) {
    if (result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode status = RelationalKey.catalogTableKey(name, physicalKey);
    if (!status.isOk()) {
      return status;
    }
    status = session.fetchByKey(physicalKey.key(), catalogRow);
    return status.isOk()
        ? CatalogRecord.decodeTable(
            catalogRow, catalogScratch, name, database, result)
        : status;
  }

  /** Adds one catalog table entry within this session's active transaction. */
  public StatusCode createTable(CharSequence name, TableDefinition result) {
    return createTable(name, "key", "value", result);
  }

  /** Adds one two-BIGINT-column catalog table entry within the active transaction. */
  public StatusCode createTable(
      CharSequence name,
      CharSequence keyColumnName,
      CharSequence valueColumnName,
      TableDefinition result) {
    schemaScratch.reset();
    StatusCode status = schemaScratch.addBigint(keyColumnName);
    if (status.isOk()) {
      status = schemaScratch.addBigint(valueColumnName);
    }
    return status.isOk()
        ? createTable(name, schemaScratch, result) : status;
  }

  public StatusCode createTable(
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
    StatusCode status = RelationalKey.catalogTableKey(name, physicalKey);
    if (status.isOk()) {
      status = session.fetchByKey(physicalKey.key(), catalogRow);
      if (status.isOk()) {
        status = StatusCode.CONFLICT;
      } else if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
      }
    }
    if (status.isOk()) {
      status = session.fetchByKey(RelationalKey.CATALOG_SEQUENCE_KEY, catalogRow);
    }
    if (status.isOk()) {
      status = CatalogRecord.decodeSequence(catalogRow, catalogScratch, nextTableId);
    }
    int tableId = nextTableId.value();
    if (status.isOk() && tableId > RelationalKey.MAXIMUM_TABLE_ID) {
      status = StatusCode.RESOURCE_EXHAUSTED;
    }
    if (status.isOk()) {
      CatalogRecord.encodeSequence(catalogOutput, tableId + 1);
      status = session.update(RelationalKey.CATALOG_SEQUENCE_KEY, catalogOutput);
    }
    if (status.isOk()) {
      CatalogRecord.encodeTable(
          catalogOutput,
          tableId,
          0,
          TableDefinition.INDEX_NONE,
          -1,
          name,
          schema);
      status = session.insert(physicalKey.key(), catalogOutput);
    }
    if (status.isOk()) {
      result.set(
          database,
          tableId,
          0,
          TableDefinition.INDEX_NONE,
          -1,
          schema);
    }
    return status;
  }

  public StatusCode insert(TableDefinition table, long key, ByteBuffer row) {
    StatusCode status = resolveWriteKey(table, key);
    return status.isOk() ? session.insert(physicalKey.key(), row) : status;
  }

  /** Builds and publishes a unique value index as part of this transaction. */
  public StatusCode createUniqueValueIndex(
      CharSequence indexName,
      CharSequence tableName) {
    return createUniqueValueIndex(indexName, tableName, "value");
  }

  public StatusCode createUniqueValueIndex(
      CharSequence indexName,
      CharSequence tableName,
      CharSequence columnName) {
    return createValueIndex(indexName, tableName, columnName, true);
  }

  public StatusCode createValueIndex(
      CharSequence indexName,
      CharSequence tableName,
      CharSequence columnName,
      boolean unique) {
    if (!registeredTransaction
        || !RelationalKey.validName(indexName)
        || !RelationalKey.validName(tableName)
        || !RelationalKey.validName(columnName)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    boolean acquired = false;
    StatusCode status = StatusCode.OK;
    if (!schemaChangeActive) {
      status = database.beginSchemaChange(this);
      if (status.isOk()) {
        schemaChangeMutationStart = session.pendingMutationCount();
        schemaChangeActive = true;
        acquired = true;
      }
    }
    if (status.isOk()) {
      status = database.buildValueIndex(
          this, indexName, tableName, columnName, unique);
    }
    if (!status.isOk() && acquired) {
      database.completeSchemaChange(this, false);
      schemaChangeActive = false;
      schemaChangeMutationStart = 0;
    }
    return status;
  }

  public StatusCode update(TableDefinition table, long key, ByteBuffer row) {
    StatusCode status = resolveWriteKey(table, key);
    return status.isOk() ? session.update(physicalKey.key(), row) : status;
  }

  public StatusCode delete(TableDefinition table, long key) {
    StatusCode status = resolveWriteKey(table, key);
    return status.isOk() ? session.delete(physicalKey.key()) : status;
  }

  public StatusCode fetch(TableDefinition table, long key, HeapRowResult result) {
    StatusCode status = resolveKey(table, key);
    return status.isOk() ? session.fetchByKey(physicalKey.key(), result) : status;
  }

  public StatusCode insertLong(
      TableDefinition table,
      long key,
      long value,
      ByteBuffer row) {
    return insertRow(table, key, row);
  }

  public StatusCode insertRow(TableDefinition table, long key, ByteBuffer row) {
    if (!validRow(table, row)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = insert(table, key, row);
    for (int slot = 0; status.isOk() && slot < table.uniqueIndexCount(); slot++) {
      if (table.uniqueIndexState(slot) != TableDefinition.INDEX_READY) {
        continue;
      }
      prepareValueIndex(table, slot);
      if (table.indexIsUnique(slot)) {
        encodeLong(indexRow, key);
        status = insertIndexedValue(
            valueIndexTable, indexedValue(table, row, slot), indexRow);
      } else {
        status = insertNonUniqueIndexedValue(
            valueIndexTable, indexedValue(table, row, slot), key);
      }
    }
    return status;
  }

  public StatusCode updateLong(
      TableDefinition table,
      long key,
      long value,
      ByteBuffer row) {
    return updateRow(table, key, row);
  }

  public StatusCode updateRow(TableDefinition table, long key, ByteBuffer row) {
    if (!validRow(table, row)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = StatusCode.OK;
    if (table.hasUniqueValueIndex()) {
      status = fetch(table, key, indexedKeyRow);
      if (status.isOk()) {
        status = copyRow(table, indexedKeyRow, rowScratch);
        for (int slot = 0; status.isOk() && slot < table.uniqueIndexCount(); slot++) {
          previousIndexedValues[slot] = indexedValue(table, rowScratch, slot);
        }
      }
    }
    if (status.isOk()) {
      status = update(table, key, row);
    }
    for (int slot = 0; status.isOk() && slot < table.uniqueIndexCount(); slot++) {
      if (table.uniqueIndexState(slot) != TableDefinition.INDEX_READY) {
        continue;
      }
      long nextValue = indexedValue(table, row, slot);
      if (previousIndexedValues[slot] == nextValue) {
        continue;
      }
      prepareValueIndex(table, slot);
      status = table.indexIsUnique(slot)
          ? deleteIndexedValue(valueIndexTable, previousIndexedValues[slot])
          : deleteNonUniqueIndexedValue(
              valueIndexTable, previousIndexedValues[slot], key);
      if (status.isOk()) {
        if (table.indexIsUnique(slot)) {
          encodeLong(indexRow, key);
          status = insertIndexedValue(valueIndexTable, nextValue, indexRow);
        } else {
          status = insertNonUniqueIndexedValue(valueIndexTable, nextValue, key);
        }
      }
    }
    return status;
  }

  public StatusCode deleteLong(TableDefinition table, long key) {
    StatusCode status = StatusCode.OK;
    if (table.hasUniqueValueIndex()) {
      status = fetch(table, key, indexedKeyRow);
      if (status.isOk()) {
        status = copyRow(table, indexedKeyRow, rowScratch);
        for (int slot = 0; status.isOk() && slot < table.uniqueIndexCount(); slot++) {
          previousIndexedValues[slot] = indexedValue(table, rowScratch, slot);
        }
      }
    }
    if (status.isOk()) {
      status = delete(table, key);
    }
    for (int slot = 0; status.isOk() && slot < table.uniqueIndexCount(); slot++) {
      if (table.uniqueIndexState(slot) != TableDefinition.INDEX_READY) {
        continue;
      }
      prepareValueIndex(table, slot);
      status = table.indexIsUnique(slot)
          ? deleteIndexedValue(valueIndexTable, previousIndexedValues[slot])
          : deleteNonUniqueIndexedValue(
              valueIndexTable, previousIndexedValues[slot], key);
    }
    return status;
  }

  public StatusCode fetchByUniqueValue(
      TableDefinition table,
      long value,
      ValueIndexLookupResult result) {
    int slot = table == null ? -1 : firstReadyIndexSlot(table);
    return slot < 0
        ? StatusCode.INVALID_EXTERNAL_INPUT
        : fetchByUniqueValue(table, table.uniqueIndexColumn(slot), value, result);
  }

  public StatusCode fetchByUniqueValue(
      TableDefinition table,
      int column,
      long value,
      ValueIndexLookupResult result) {
    int slot = table == null ? -1 : table.readyIndexSlotOn(column);
    if (table == null
        || !table.isOwnedBy(database)
        || slot < 0
        || !table.indexIsUnique(slot)
        || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    prepareValueIndex(table, slot);
    StatusCode status = fetchIndexedValue(valueIndexTable, value, indexedKeyRow);
    if (status.isOk()) {
      status = decodeLong(indexedKeyRow, valueScratch);
    }
    long primaryKey = status.isOk() ? valueScratch.getLong(0) : 0;
    if (status.isOk()) {
      status = fetch(table, primaryKey, result.row());
      if (status == StatusCode.CONFLICT) {
        return StatusCode.CORRUPTION;
      }
    }
    if (status.isOk()) {
      status = copyRow(table, result.row(), rowScratch);
    }
    if (status.isOk() && indexedValue(table, rowScratch, slot) != value) {
      return StatusCode.CORRUPTION;
    }
    if (status.isOk()) {
      result.setKey(primaryKey);
    }
    return status;
  }

  public StatusCode beginValueScan(
      TableDefinition table,
      int column,
      long lowerInclusive,
      long upperExclusive,
      RelationalScanCursor cursor) {
    int slot = table == null ? -1 : table.readyIndexSlotOn(column);
    boolean unique = slot >= 0 && table.indexIsUnique(slot);
    if (table == null
        || !table.isOwnedBy(database)
        || slot < 0
        || (unique
            ? !validIndexedValue(lowerInclusive)
            : !validNonUniqueIndexedValue(lowerInclusive))
        || upperExclusive <= lowerInclusive
        || upperExclusive > (unique
            ? MAXIMUM_INDEXED_VALUE_EXCLUSIVE
            : MAXIMUM_NON_UNIQUE_VALUE_EXCLUSIVE)
        || cursor == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    prepareValueIndex(table, slot);
    StatusCode status = beginScan(
        valueIndexTable,
        unique
            ? normalizeIndexedValue(lowerInclusive)
            : normalizeNonUniqueIndexedValue(lowerInclusive),
        unique
            ? normalizeIndexedValue(upperExclusive)
            : normalizeNonUniqueIndexedValue(upperExclusive),
        cursor);
    return status.isOk()
        ? cursor.setIndexedColumn(this, column, unique) : status;
  }

  public StatusCode nextValueScan(
      TableDefinition table,
      RelationalScanCursor cursor,
      RelationalScanResult indexResult,
      ValueIndexLookupResult result) {
    if (table == null
        || !table.isOwnedBy(database)
        || table.readyIndexSlotOn(cursor == null ? -1 : cursor.indexedColumn()) < 0
        || cursor == null
        || indexResult == null
        || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    int slot = table.readyIndexSlotOn(cursor.indexedColumn());
    if (!cursor.uniqueIndex()) {
      return nextNonUniqueValueScan(table, cursor, indexResult, result, slot);
    }
    StatusCode status = nextScan(cursor, indexResult);
    if (status.isOk()) {
      status = decodeLong(indexResult.row(), valueScratch);
    }
    long primaryKey = status.isOk() ? valueScratch.getLong(0) : 0;
    long value = status.isOk() ? denormalizeIndexedValue(indexResult.key()) : 0;
    if (status.isOk()) {
      status = fetch(table, primaryKey, result.row());
      if (status == StatusCode.CONFLICT) {
        return StatusCode.CORRUPTION;
      }
    }
    if (status.isOk()) {
      status = copyRow(table, result.row(), rowScratch);
    }
    if (status.isOk() && indexedValue(table, rowScratch, slot) != value) {
      return StatusCode.CORRUPTION;
    }
    if (status.isOk()) {
      result.setKey(primaryKey);
    }
    return status;
  }

  private StatusCode nextNonUniqueValueScan(
      TableDefinition table,
      RelationalScanCursor cursor,
      RelationalScanResult indexResult,
      ValueIndexLookupResult result,
      int slot) {
    prepareValueIndex(table, slot);
    StatusCode status = StatusCode.OK;
    while (status.isOk()) {
      if (cursor.duplicateEntryId() == 0) {
        status = nextScan(cursor, indexResult);
        if (!status.isOk()) {
          return status;
        }
        status = decodeLong(indexResult.row(), valueScratch);
        long headEntryId = status.isOk() ? valueScratch.getLong(0) : 0;
        if (status.isOk() && !validNonUniqueEntryId(headEntryId)) {
          status = StatusCode.CORRUPTION;
        }
        if (status.isOk()) {
          cursor.startDuplicateChain(
              denormalizeNonUniqueIndexedValue(indexResult.key()), headEntryId);
        }
      }
      if (status.isOk()
          && cursor.duplicateEntriesVisited() >= MAXIMUM_DUPLICATE_CHAIN) {
        status = StatusCode.CORRUPTION;
      }
      long entryId = cursor.duplicateEntryId();
      if (status.isOk()) {
        status = fetch(valueIndexTable, nonUniqueEntryKey(entryId), indexEntryRow);
        if (status == StatusCode.CONFLICT) {
          status = StatusCode.CORRUPTION;
        }
      }
      if (status.isOk()) {
        status = copyNonUniqueEntry(indexEntryRow, indexEntryScratch);
      }
      long value = status.isOk() ? indexEntryScratch.getLong(0) : 0;
      long primaryKey = status.isOk() ? indexEntryScratch.getLong(8) : 0;
      long nextEntryId = status.isOk() ? indexEntryScratch.getLong(16) : 0;
      if (status.isOk()
          && (value != cursor.duplicateValue()
              || nextEntryId < 0
              || nextEntryId > NON_UNIQUE_ENTRY_FLAG - 2)) {
        status = StatusCode.CORRUPTION;
      }
      if (status.isOk()) {
        cursor.advanceDuplicateChain(nextEntryId);
        status = fetch(table, primaryKey, result.row());
        if (status == StatusCode.CONFLICT) {
          status = StatusCode.CORRUPTION;
        }
      }
      if (status.isOk()) {
        status = copyRow(table, result.row(), rowScratch);
      }
      if (status.isOk() && indexedValue(table, rowScratch, slot) != value) {
        status = StatusCode.CORRUPTION;
      }
      if (status.isOk()) {
        result.setKey(primaryKey);
        return StatusCode.OK;
      }
    }
    return status;
  }

  public StatusCode beginScan(TableDefinition table, RelationalScanCursor cursor) {
    return beginScan(table, 0, RelationalKey.USER_KEY_MASK, cursor);
  }

  public StatusCode beginScan(
      TableDefinition table,
      long lowerInclusive,
      long upperExclusive,
      RelationalScanCursor cursor) {
    if (table == null
        || !table.isOwnedBy(database)
        || lowerInclusive < 0
        || lowerInclusive > RelationalKey.MAXIMUM_USER_KEY
        || upperExclusive <= lowerInclusive
        || upperExclusive > RelationalKey.USER_KEY_MASK
        || cursor == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    long tableKey = (long) table.tableId() << 48;
    long lowerKey = tableKey | lowerInclusive;
    long upperKey = tableKey | upperExclusive;
    StatusCode status = session.beginScan(lowerKey, upperKey, cursor.indexed());
    return status.isOk() ? cursor.claim(this) : status;
  }

  public StatusCode nextScan(
      RelationalScanCursor cursor,
      RelationalScanResult result) {
    if (cursor == null || !cursor.isOwnedBy(this) || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode status = session.nextScan(cursor.indexed(), result.indexed());
    if (status.isOk()) {
      result.set(result.indexed().key() & RelationalKey.USER_KEY_MASK);
    }
    return status;
  }

  public StatusCode closeScan(RelationalScanCursor cursor) {
    if (cursor == null || !cursor.isOwnedBy(this)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = session.closeScan(cursor.indexed());
    if (status.isOk()) {
      cursor.complete();
    }
    return status;
  }

  public StatusCode createSavepoint(IndexedSavepoint savepoint) {
    return session.createSavepoint(savepoint);
  }

  public StatusCode rollbackToSavepoint(IndexedSavepoint savepoint) {
    StatusCode status = session.rollbackToSavepoint(savepoint);
    if (status.isOk()
        && schemaChangeActive
        && session.pendingMutationCount() <= schemaChangeMutationStart) {
      database.completeSchemaChange(this, false);
      schemaChangeActive = false;
      schemaChangeMutationStart = 0;
    }
    return status;
  }

  public StatusCode releaseSavepoint(IndexedSavepoint savepoint) {
    return session.releaseSavepoint(savepoint);
  }

  public StatusCode commit(TransactionOutcome result) {
    StatusCode status = session.commit(result);
    completeTerminalSchemaChange(status.isOk()
        && result.isAvailable()
        && result.state() == TransactionState.COMMITTED);
    releaseTerminalTransaction();
    return status;
  }

  public StatusCode abort(TransactionOutcome result) {
    StatusCode status = session.abort(result);
    completeTerminalSchemaChange(false);
    releaseTerminalTransaction();
    return status;
  }

  public long visibleCommitSequence() {
    return session.transaction().snapshot().visibleCommitSequence();
  }

  IndexedTransactionSession indexedSession() {
    return session;
  }

  StatusCode commitBuildPhase(TransactionOutcome result) {
    StatusCode status = session.commit(result);
    releaseTerminalTransaction();
    return status;
  }

  StatusCode abortBuildPhase(TransactionOutcome result) {
    StatusCode status = session.abort(result);
    releaseTerminalTransaction();
    return status;
  }

  StatusCode beginPersistentSchemaChange() {
    if (!registeredTransaction || schemaChangeActive) {
      return StatusCode.CONFLICT;
    }
    StatusCode status = database.beginSchemaChange(this);
    if (status.isOk()) {
      schemaChangeMutationStart = session.pendingMutationCount();
      schemaChangeActive = true;
    }
    return status;
  }

  void releasePersistentSchemaChange() {
    if (schemaChangeActive && !session.transaction().isActiveHandle()) {
      database.completeSchemaChange(this, false);
      schemaChangeActive = false;
      schemaChangeMutationStart = 0;
    }
  }

  private StatusCode resolveKey(TableDefinition table, long key) {
    if (table == null || !table.isOwnedBy(database)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return RelationalKey.tableRowKey(table.tableId(), key, physicalKey);
  }

  private StatusCode resolveWriteKey(TableDefinition table, long key) {
    if (table != null && table.hasBuildingUniqueValueIndex()) {
      return StatusCode.RETRY;
    }
    return resolveKey(table, key);
  }

  private void prepareValueIndex(TableDefinition table, int slot) {
    valueIndexTable.set(
        database,
        table.uniqueIndexTableId(slot),
        0,
        TableDefinition.INDEX_NONE);
  }

  StatusCode insertIndexedValue(
      TableDefinition indexTable,
      long value,
      ByteBuffer row) {
    return validIndexedValue(value)
        ? insert(indexTable, normalizeIndexedValue(value), row)
        : StatusCode.INVALID_EXTERNAL_INPUT;
  }

  StatusCode ensureIndexedValue(
      TableDefinition indexTable,
      long value,
      long primaryKey) {
    if (!validIndexedValue(value)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = fetchIndexedValue(indexTable, value, indexedKeyRow);
    if (status == StatusCode.CONFLICT) {
      encodeLong(indexRow, primaryKey);
      return insertIndexedValue(indexTable, value, indexRow);
    }
    if (!status.isOk()) {
      return status;
    }
    status = decodeLong(indexedKeyRow, valueScratch);
    return status.isOk() && valueScratch.getLong(0) == primaryKey
        ? StatusCode.OK : StatusCode.CONFLICT;
  }

  StatusCode insertNonUniqueIndexedValue(
      TableDefinition indexTable,
      long value,
      long primaryKey) {
    if (!validNonUniqueIndexedValue(value)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = fetch(indexTable, NON_UNIQUE_ALLOCATOR_KEY, indexAllocatorRow);
    long entryId = 1;
    if (status.isOk()) {
      status = decodeLong(indexAllocatorRow, valueScratch);
      entryId = status.isOk() ? valueScratch.getLong(0) : 0;
      if (status.isOk()
          && (entryId <= 0 || entryId > NON_UNIQUE_ENTRY_FLAG - 2)) {
        status = entryId == NON_UNIQUE_ENTRY_FLAG - 1
            ? StatusCode.RESOURCE_EXHAUSTED : StatusCode.CORRUPTION;
      }
      if (status.isOk()) {
        encodeLong(indexRow, entryId + 1);
        status = update(indexTable, NON_UNIQUE_ALLOCATOR_KEY, indexRow);
      }
    } else if (status == StatusCode.CONFLICT) {
      encodeLong(indexRow, 2);
      status = insert(indexTable, NON_UNIQUE_ALLOCATOR_KEY, indexRow);
    }
    long headKey = normalizeNonUniqueIndexedValue(value);
    long previousHead = 0;
    boolean headExists = false;
    if (status.isOk()) {
      status = fetch(indexTable, headKey, indexHeadRow);
      if (status.isOk()) {
        headExists = true;
        status = decodeLong(indexHeadRow, valueScratch);
        previousHead = status.isOk() ? valueScratch.getLong(0) : 0;
        if (status.isOk() && !validNonUniqueEntryId(previousHead)) {
          status = StatusCode.CORRUPTION;
        }
      } else if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
      }
    }
    if (status.isOk()) {
      encodeNonUniqueEntry(nonUniqueIndexRow, value, primaryKey, previousHead);
      status = insert(indexTable, nonUniqueEntryKey(entryId), nonUniqueIndexRow);
    }
    if (status.isOk()) {
      encodeLong(indexRow, entryId);
      status = headExists
          ? update(indexTable, headKey, indexRow)
          : insert(indexTable, headKey, indexRow);
    }
    return status;
  }

  StatusCode ensureNonUniqueIndexedValue(
      TableDefinition indexTable,
      long value,
      long primaryKey) {
    if (!validNonUniqueIndexedValue(value)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    long headKey = normalizeNonUniqueIndexedValue(value);
    StatusCode status = fetch(indexTable, headKey, indexHeadRow);
    if (status == StatusCode.CONFLICT) {
      return insertNonUniqueIndexedValue(indexTable, value, primaryKey);
    }
    if (status.isOk()) {
      status = decodeLong(indexHeadRow, valueScratch);
    }
    long entryId = status.isOk() ? valueScratch.getLong(0) : 0;
    if (status.isOk() && !validNonUniqueEntryId(entryId)) {
      status = StatusCode.CORRUPTION;
    }
    int visited = 0;
    while (status.isOk() && entryId != 0) {
      if (visited++ >= MAXIMUM_DUPLICATE_CHAIN) {
        status = StatusCode.CORRUPTION;
        break;
      }
      status = fetch(indexTable, nonUniqueEntryKey(entryId), indexEntryRow);
      if (status == StatusCode.CONFLICT) {
        status = StatusCode.CORRUPTION;
      }
      if (status.isOk()) {
        status = copyNonUniqueEntry(indexEntryRow, indexEntryScratch);
      }
      long storedValue = status.isOk() ? indexEntryScratch.getLong(0) : 0;
      long storedPrimaryKey = status.isOk() ? indexEntryScratch.getLong(8) : 0;
      long nextEntryId = status.isOk() ? indexEntryScratch.getLong(16) : 0;
      if (status.isOk()
          && (storedValue != value
              || nextEntryId < 0
              || nextEntryId > NON_UNIQUE_ENTRY_FLAG - 2)) {
        status = StatusCode.CORRUPTION;
      }
      if (status.isOk() && storedPrimaryKey == primaryKey) {
        return StatusCode.OK;
      }
      entryId = nextEntryId;
    }
    return status.isOk()
        ? insertNonUniqueIndexedValue(indexTable, value, primaryKey) : status;
  }

  private StatusCode deleteNonUniqueIndexedValue(
      TableDefinition indexTable,
      long value,
      long primaryKey) {
    if (!validNonUniqueIndexedValue(value)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    long headKey = normalizeNonUniqueIndexedValue(value);
    StatusCode status = fetch(indexTable, headKey, indexHeadRow);
    if (!status.isOk()) {
      return status == StatusCode.CONFLICT ? StatusCode.CORRUPTION : status;
    }
    status = decodeLong(indexHeadRow, valueScratch);
    long entryId = status.isOk() ? valueScratch.getLong(0) : 0;
    if (status.isOk() && !validNonUniqueEntryId(entryId)) {
      status = StatusCode.CORRUPTION;
    }
    long previousEntryId = 0;
    long nextEntryId = 0;
    boolean found = false;
    int visited = 0;
    while (status.isOk() && entryId != 0) {
      if (visited++ >= MAXIMUM_DUPLICATE_CHAIN) {
        status = StatusCode.CORRUPTION;
        break;
      }
      status = fetch(indexTable, nonUniqueEntryKey(entryId), indexEntryRow);
      if (status == StatusCode.CONFLICT) {
        status = StatusCode.CORRUPTION;
      }
      if (status.isOk()) {
        status = copyNonUniqueEntry(indexEntryRow, indexEntryScratch);
      }
      long storedValue = status.isOk() ? indexEntryScratch.getLong(0) : 0;
      long storedPrimaryKey = status.isOk() ? indexEntryScratch.getLong(8) : 0;
      nextEntryId = status.isOk() ? indexEntryScratch.getLong(16) : 0;
      if (status.isOk()
          && (storedValue != value
              || nextEntryId < 0
              || nextEntryId > NON_UNIQUE_ENTRY_FLAG - 2)) {
        status = StatusCode.CORRUPTION;
      }
      if (status.isOk() && storedPrimaryKey == primaryKey) {
        found = true;
        break;
      }
      previousEntryId = entryId;
      entryId = nextEntryId;
    }
    if (status.isOk() && !found) {
      status = StatusCode.CORRUPTION;
    }
    if (status.isOk() && previousEntryId == 0) {
      if (nextEntryId == 0) {
        status = delete(indexTable, headKey);
      } else {
        encodeLong(indexRow, nextEntryId);
        status = update(indexTable, headKey, indexRow);
      }
    } else if (status.isOk()) {
      status = fetch(
          indexTable, nonUniqueEntryKey(previousEntryId), indexEntryRow);
      if (status.isOk()) {
        status = copyNonUniqueEntry(indexEntryRow, indexEntryScratch);
      }
      if (status.isOk() && indexEntryScratch.getLong(16) != entryId) {
        status = StatusCode.CORRUPTION;
      }
      if (status.isOk()) {
        indexEntryScratch.putLong(16, nextEntryId);
        indexEntryScratch.position(0);
        indexEntryScratch.limit(NON_UNIQUE_ENTRY_BYTES);
        status = update(
            indexTable, nonUniqueEntryKey(previousEntryId), indexEntryScratch);
      }
    }
    if (status.isOk()) {
      status = delete(indexTable, nonUniqueEntryKey(entryId));
    }
    return status;
  }

  private StatusCode deleteIndexedValue(TableDefinition indexTable, long value) {
    return validIndexedValue(value)
        ? delete(indexTable, normalizeIndexedValue(value))
        : StatusCode.INVALID_EXTERNAL_INPUT;
  }

  private StatusCode fetchIndexedValue(
      TableDefinition indexTable,
      long value,
      HeapRowResult result) {
    return validIndexedValue(value)
        ? fetch(indexTable, normalizeIndexedValue(value), result)
        : StatusCode.INVALID_EXTERNAL_INPUT;
  }

  static boolean validIndexedValue(long value) {
    return value >= MINIMUM_INDEXED_VALUE && value <= MAXIMUM_INDEXED_VALUE;
  }

  private static boolean validNonUniqueIndexedValue(long value) {
    return value >= MINIMUM_NON_UNIQUE_VALUE && value <= MAXIMUM_NON_UNIQUE_VALUE;
  }

  private static boolean validNonUniqueEntryId(long entryId) {
    return entryId > 0 && entryId <= NON_UNIQUE_ENTRY_FLAG - 2;
  }

  private static long normalizeIndexedValue(long value) {
    return value + INDEX_VALUE_BIAS;
  }

  private static long denormalizeIndexedValue(long value) {
    return value - INDEX_VALUE_BIAS;
  }

  private static long normalizeNonUniqueIndexedValue(long value) {
    return value + NON_UNIQUE_VALUE_BIAS;
  }

  private static long denormalizeNonUniqueIndexedValue(long value) {
    return value - NON_UNIQUE_VALUE_BIAS;
  }

  private static long nonUniqueEntryKey(long entryId) {
    return NON_UNIQUE_ENTRY_FLAG | entryId;
  }

  private static void encodeNonUniqueEntry(
      ByteBuffer target,
      long value,
      long primaryKey,
      long nextEntryId) {
    target.clear();
    target.putLong(0, value);
    target.putLong(8, primaryKey);
    target.putLong(16, nextEntryId);
    target.position(0);
    target.limit(NON_UNIQUE_ENTRY_BYTES);
  }

  private static StatusCode copyNonUniqueEntry(
      HeapRowResult source,
      ByteBuffer target) {
    if (source.length() != NON_UNIQUE_ENTRY_BYTES) {
      return StatusCode.CORRUPTION;
    }
    target.clear();
    StatusCode status = source.copyTo(target);
    if (status.isOk()) {
      target.position(0);
      target.limit(NON_UNIQUE_ENTRY_BYTES);
    }
    return status;
  }

  private static void encodeLong(ByteBuffer target, long value) {
    target.clear();
    target.putLong(0, value);
    target.position(0);
    target.limit(Long.BYTES);
  }

  private static StatusCode decodeLong(HeapRowResult source, ByteBuffer target) {
    target.clear();
    StatusCode status = source.length() == Long.BYTES
        ? source.copyTo(target) : StatusCode.CORRUPTION;
    return status;
  }

  private static boolean validRow(TableDefinition table, ByteBuffer row) {
    return table != null
        && row != null
        && row.remaining() == table.rowBytes();
  }

  private static long indexedValue(TableDefinition table, ByteBuffer row, int slot) {
    return row.getLong(
        row.position() + (table.uniqueIndexColumn(slot) - 1) * Long.BYTES);
  }

  private static int firstReadyIndexSlot(TableDefinition table) {
    for (int slot = 0; slot < table.uniqueIndexCount(); slot++) {
      if (table.uniqueIndexState(slot) == TableDefinition.INDEX_READY) {
        return slot;
      }
    }
    return -1;
  }

  private static StatusCode copyRow(
      TableDefinition table,
      HeapRowResult source,
      ByteBuffer target) {
    if (source.length() != table.rowBytes()) {
      return StatusCode.CORRUPTION;
    }
    target.clear();
    target.limit(table.rowBytes());
    StatusCode status = source.copyTo(target);
    if (status.isOk()) {
      target.position(0);
    }
    return status;
  }

  private void releaseTerminalTransaction() {
    if (registeredTransaction && !session.transaction().isActiveHandle()) {
      registeredTransaction = false;
      database.leaveTransaction();
    }
  }

  private void completeTerminalSchemaChange(boolean committed) {
    if (schemaChangeActive && !session.transaction().isActiveHandle()) {
      database.completeSchemaChange(this, committed);
      schemaChangeActive = false;
      schemaChangeMutationStart = 0;
    }
  }
}
