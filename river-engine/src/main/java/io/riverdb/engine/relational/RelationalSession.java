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

  private final RelationalDatabase database;
  private final IndexedTransactionSession session;
  private final RelationalKey.LongKeyResult physicalKey = new RelationalKey.LongKeyResult();
  private final HeapRowResult catalogRow = new HeapRowResult();
  private final ByteBuffer catalogScratch = ByteBuffer.allocateDirect(CatalogRecord.MAXIMUM_BYTES);
  private final ByteBuffer catalogOutput = ByteBuffer.allocateDirect(CatalogRecord.MAXIMUM_BYTES);
  private final CatalogRecord.IntResult nextTableId = new CatalogRecord.IntResult();
  private final TableDefinition valueIndexTable = new TableDefinition();
  private final HeapRowResult indexedKeyRow = new HeapRowResult();
  private final ByteBuffer valueScratch = ByteBuffer.allocateDirect(Long.BYTES);
  private final ByteBuffer indexRow = ByteBuffer.allocateDirect(Long.BYTES);
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
    if (!RelationalKey.validName(name)
        || !RelationalKey.validName(keyColumnName)
        || !RelationalKey.validName(valueColumnName)
        || sameName(keyColumnName, valueColumnName)
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
          name,
          keyColumnName,
          valueColumnName);
      status = session.insert(physicalKey.key(), catalogOutput);
    }
    if (status.isOk()) {
      result.set(
          database,
          tableId,
          0,
          TableDefinition.INDEX_NONE,
          keyColumnName,
          valueColumnName);
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
      status = database.buildUniqueValueIndex(this, indexName, tableName, columnName);
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
    StatusCode status = insert(table, key, row);
    if (status.isOk() && table.hasUniqueValueIndex()) {
      prepareValueIndex(table);
      encodeLong(indexRow, key);
      status = insertIndexedValue(valueIndexTable, value, indexRow);
    }
    return status;
  }

  public StatusCode updateLong(
      TableDefinition table,
      long key,
      long value,
      ByteBuffer row) {
    long previousValue = 0;
    StatusCode status = StatusCode.OK;
    if (table.hasUniqueValueIndex()) {
      status = fetch(table, key, indexedKeyRow);
      if (status.isOk()) {
        status = decodeLong(indexedKeyRow, valueScratch);
        previousValue = status.isOk() ? valueScratch.getLong(0) : 0;
      }
    }
    if (status.isOk()) {
      status = update(table, key, row);
    }
    if (status.isOk() && table.hasUniqueValueIndex() && previousValue != value) {
      prepareValueIndex(table);
      status = deleteIndexedValue(valueIndexTable, previousValue);
      if (status.isOk()) {
        encodeLong(indexRow, key);
        status = insertIndexedValue(valueIndexTable, value, indexRow);
      }
    }
    return status;
  }

  public StatusCode deleteLong(TableDefinition table, long key) {
    long previousValue = 0;
    StatusCode status = StatusCode.OK;
    if (table.hasUniqueValueIndex()) {
      status = fetch(table, key, indexedKeyRow);
      if (status.isOk()) {
        status = decodeLong(indexedKeyRow, valueScratch);
        previousValue = status.isOk() ? valueScratch.getLong(0) : 0;
      }
    }
    if (status.isOk()) {
      status = delete(table, key);
    }
    if (status.isOk() && table.hasUniqueValueIndex()) {
      prepareValueIndex(table);
      status = deleteIndexedValue(valueIndexTable, previousValue);
    }
    return status;
  }

  public StatusCode fetchByUniqueValue(
      TableDefinition table,
      long value,
      ValueIndexLookupResult result) {
    if (table == null
        || !table.isOwnedBy(database)
        || !table.hasUniqueValueIndex()
        || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    prepareValueIndex(table);
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
      status = decodeLong(result.row(), valueScratch);
    }
    if (status.isOk() && valueScratch.getLong(0) != value) {
      return StatusCode.CORRUPTION;
    }
    if (status.isOk()) {
      result.setKey(primaryKey);
    }
    return status;
  }

  public StatusCode beginValueScan(
      TableDefinition table,
      long lowerInclusive,
      long upperExclusive,
      RelationalScanCursor cursor) {
    if (table == null
        || !table.isOwnedBy(database)
        || !table.hasUniqueValueIndex()
        || !validIndexedValue(lowerInclusive)
        || upperExclusive <= lowerInclusive
        || upperExclusive > MAXIMUM_INDEXED_VALUE_EXCLUSIVE
        || cursor == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    prepareValueIndex(table);
    return beginScan(
        valueIndexTable,
        normalizeIndexedValue(lowerInclusive),
        normalizeIndexedValue(upperExclusive),
        cursor);
  }

  public StatusCode nextValueScan(
      TableDefinition table,
      RelationalScanCursor cursor,
      RelationalScanResult indexResult,
      ValueIndexLookupResult result) {
    if (table == null
        || !table.isOwnedBy(database)
        || !table.hasUniqueValueIndex()
        || cursor == null
        || indexResult == null
        || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
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
      status = decodeLong(result.row(), valueScratch);
    }
    if (status.isOk() && valueScratch.getLong(0) != value) {
      return StatusCode.CORRUPTION;
    }
    if (status.isOk()) {
      result.setKey(primaryKey);
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

  private void prepareValueIndex(TableDefinition table) {
    valueIndexTable.set(
        database,
        table.uniqueValueIndexTableId(),
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

  private static long normalizeIndexedValue(long value) {
    return value + INDEX_VALUE_BIAS;
  }

  private static long denormalizeIndexedValue(long value) {
    return value - INDEX_VALUE_BIAS;
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

  private static boolean sameName(CharSequence first, CharSequence second) {
    if (first.length() != second.length()) {
      return false;
    }
    for (int index = 0; index < first.length(); index++) {
      if (first.charAt(index) != second.charAt(index)) {
        return false;
      }
    }
    return true;
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
