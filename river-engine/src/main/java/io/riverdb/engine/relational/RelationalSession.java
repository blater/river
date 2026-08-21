package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.engine.table.IndexedSavepoint;
import io.riverdb.engine.table.IndexedScanResult;
import io.riverdb.storage.heap.HeapRowResult;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import io.riverdb.tx.api.TransactionState;
import java.nio.ByteBuffer;

/** Transaction session over catalog-resolved logical tables in one physical keyspace. */
public final class RelationalSession {
  private static final int PENDING_DROP_NONE = 0;
  private static final int PENDING_DROP_INDEX = 1;
  private static final int PENDING_DROP_TABLE = 2;

  private final RelationalSchemaLifecycle schemaLifecycle;
  private final RelationalSchemaGate schemaGate;
  private final IndexedTransactionSession session;
  private final RelationalSecondaryIndexStore secondaryIndexes;
  private final RelationalIndexLookup indexLookup;
  private final RelationalCatalogDdl catalogDdl;
  private final RelationalCatalogReader catalogReader;
  private final RelationalReferentialIntegrity referentialIntegrity;
  private final RelationalRowMutation rowMutations;
  private final TableSchema.ColumnName pendingDropIndexName =
      new TableSchema.ColumnName();
  private final TableSchema.ColumnName pendingDropTableName =
      new TableSchema.ColumnName();
  private final TransactionOutcome schemaCleanupOutcome = new TransactionOutcome();
  private boolean registeredTransaction;
  private boolean schemaChangeActive;
  private int schemaChangeMutationStart;
  private int pendingDropMutationStart;
  private int pendingDropType;

  RelationalSession(
      RelationalSchemaLifecycle lifecycle,
      RelationalSchemaGate gate,
      IndexedTransactionSession indexedSession) {
    schemaLifecycle = lifecycle;
    schemaGate = gate;
    session = indexedSession;
    secondaryIndexes = new RelationalSecondaryIndexStore(gate, indexedSession);
    indexLookup = new RelationalIndexLookup(gate, indexedSession);
    catalogDdl = new RelationalCatalogDdl(gate);
    catalogReader = new RelationalCatalogReader(gate, indexedSession);
    referentialIntegrity = new RelationalReferentialIntegrity(gate);
    rowMutations = new RelationalRowMutation(gate, indexedSession, secondaryIndexes);
  }

  public boolean isTransactionActive() {
    return registeredTransaction;
  }

  public StatusCode begin(IsolationLevel isolationLevel) {
    if (registeredTransaction) {
      return StatusCode.CONFLICT;
    }
    StatusCode status = schemaGate.enterTransaction(this);
    boolean entered = status.isOk();
    if (status.isOk()) {
      status = session.begin(isolationLevel);
    }
    if (status.isOk()) {
      registeredTransaction = true;
    } else if (entered) {
      schemaGate.leaveTransaction();
    }
    return status;
  }

  public StatusCode beginStatement() {
    return session.beginStatement();
  }

  public StatusCode completeStatement() {
    return session.completeStatement();
  }

  public StatusCode resolveTable(CharSequence name, TableDefinition result) {
    return catalogReader.resolveTable(name, result);
  }

  public StatusCode resolveView(
      CharSequence name,
      ViewDefinition result) {
    return catalogReader.resolveView(name, result);
  }

  public StatusCode beginCatalogObjectScan(CatalogObjectCursor cursor) {
    if (!registeredTransaction) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return catalogReader.beginObjectScan(this, cursor);
  }

  public StatusCode nextCatalogObject(
      CatalogObjectCursor cursor,
      CatalogObjectResult result) {
    return catalogReader.nextObject(this, cursor, result);
  }

  public StatusCode closeCatalogObjectScan(CatalogObjectCursor cursor) {
    return catalogReader.closeObjectScan(this, cursor);
  }

  public StatusCode beginCatalogIndexScan(
      CharSequence tableName,
      CatalogIndexCursor cursor) {
    if (!registeredTransaction) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return catalogReader.beginIndexScan(this, tableName, cursor);
  }

  public StatusCode nextCatalogIndex(
      CatalogIndexCursor cursor,
      CatalogIndexResult result) {
    return catalogReader.nextIndex(this, cursor, result);
  }

  public StatusCode closeCatalogIndexScan(CatalogIndexCursor cursor) {
    return catalogReader.closeIndexScan(this, cursor);
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
    if (!registeredTransaction || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    if (pendingDropType != PENDING_DROP_NONE) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    boolean acquired = !schemaChangeActive;
    StatusCode status = acquireSchemaChange();
    if (status.isOk()) {
      status = catalogDdl.createTable(
          this, name, keyColumnName, valueColumnName, result);
    }
    finishFailedSchemaCreation(status, acquired, result);
    return status;
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
    if (!registeredTransaction) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (pendingDropType != PENDING_DROP_NONE) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    boolean acquired = !schemaChangeActive;
    StatusCode status = acquireSchemaChange();
    if (status.isOk()) {
      status = catalogDdl.createTable(this, name, schema, result);
    }
    finishFailedSchemaCreation(status, acquired, result);
    return status;
  }

  private StatusCode acquireSchemaChange() {
    if (schemaChangeActive) {
      return StatusCode.OK;
    }
    StatusCode status = schemaGate.beginSchemaChange(this);
    if (status.isOk()) {
      schemaChangeMutationStart = session.pendingMutationCount();
      schemaChangeActive = true;
    }
    return status;
  }

  private void finishFailedSchemaCreation(
      StatusCode status, boolean acquired, TableDefinition result) {
    if (status.isOk()) {
      return;
    }
    result.reset();
    finishFailedSchemaChange(status, acquired);
  }

  private void finishFailedSchemaChange(StatusCode status, boolean acquired) {
    if (status.isOk() || !acquired) {
      return;
    }
    schemaGate.completeSchemaChange(this, false);
    schemaChangeActive = false;
    schemaChangeMutationStart = 0;
  }

  public StatusCode createSequence(
      CharSequence name,
      long start,
      long increment) {
    if (!registeredTransaction
        || !RelationalKey.validName(name)
        || increment == 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (pendingDropType != PENDING_DROP_NONE) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    boolean acquired = !schemaChangeActive;
    StatusCode status = acquireSchemaChange();
    if (status.isOk()) {
      status = catalogDdl.createSequence(this, name, start, increment);
    }
    finishFailedSchemaChange(status, acquired);
    return status;
  }

  public StatusCode createView(
      CharSequence name,
      CharSequence query,
      int[] tableIds,
      int tableCount) {
    if (!registeredTransaction
        || !RelationalKey.validName(name)
        || query == null
        || query.length() <= 0
        || query.length() > ViewDefinition.MAXIMUM_QUERY_LENGTH
        || !validViewLineage(tableIds, tableCount)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (pendingDropType != PENDING_DROP_NONE) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    boolean acquired = !schemaChangeActive;
    StatusCode status = acquireSchemaChange();
    if (status.isOk()) {
      status = catalogDdl.createView(
          this, name, query, tableIds, tableCount);
    }
    finishFailedSchemaChange(status, acquired);
    return status;
  }

  private static boolean validViewLineage(int[] tableIds, int tableCount) {
    if (tableIds == null
        || tableCount < 1
        || tableCount > ViewDefinition.MAXIMUM_LINEAGE_TABLES
        || tableCount > tableIds.length) {
      return false;
    }
    for (int index = 0; index < tableCount; index++) {
      if (tableIds[index] <= 0
          || tableIds[index] > RelationalKey.MAXIMUM_TABLE_ID) return false;
    }
    return true;
  }

  public StatusCode dropView(CharSequence name) {
    if (!registeredTransaction || !RelationalKey.validName(name)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (pendingDropType != PENDING_DROP_NONE) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    boolean acquired = !schemaChangeActive;
    StatusCode status = acquireSchemaChange();
    if (status.isOk()) {
      status = catalogDdl.dropView(this, name);
    }
    finishFailedSchemaChange(status, acquired);
    return status;
  }

  public StatusCode dropSequence(CharSequence name) {
    if (!registeredTransaction || !RelationalKey.validName(name)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (pendingDropType != PENDING_DROP_NONE) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    boolean acquired = !schemaChangeActive;
    StatusCode status = acquireSchemaChange();
    if (status.isOk()) {
      status = catalogDdl.dropSequence(this, name);
    }
    finishFailedSchemaChange(status, acquired);
    return status;
  }

  StatusCode insert(TableDefinition table, long key, ByteBuffer row) {
    return rowMutations.insert(table, key, row);
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
    return createValueIndex(indexName, tableName, columnName, unique, false);
  }

  public StatusCode createUniqueConstraintIndex(
      CharSequence indexName,
      CharSequence tableName,
      CharSequence columnName) {
    return createValueIndex(indexName, tableName, columnName, true, true);
  }

  public StatusCode createConstraintIndex(
      CharSequence indexName,
      CharSequence tableName,
      CharSequence columnName,
      boolean unique) {
    return createValueIndex(indexName, tableName, columnName, unique, true);
  }

  private StatusCode createValueIndex(
      CharSequence indexName,
      CharSequence tableName,
      CharSequence columnName,
      boolean unique,
      boolean constraint) {
    if (!registeredTransaction
        || !RelationalKey.validName(indexName)
        || !RelationalKey.validName(tableName)
        || !RelationalKey.validName(columnName)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (pendingDropType != PENDING_DROP_NONE) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    boolean acquired = !schemaChangeActive;
    StatusCode status = acquireSchemaChange();
    if (status.isOk()) {
      status = schemaLifecycle.buildValueIndex(
          this, indexName, tableName, columnName, unique, constraint);
    }
    finishFailedSchemaChange(status, acquired);
    return status;
  }

  public StatusCode dropValueIndex(
      CharSequence indexName,
      CharSequence tableName) {
    if (!registeredTransaction
        || !RelationalKey.validName(indexName)
        || !RelationalKey.validName(tableName)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (pendingDropType != PENDING_DROP_NONE) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    boolean acquired = !schemaChangeActive;
    StatusCode status = acquireSchemaChange();
    int mutationStart = session.pendingMutationCount();
    if (status.isOk()) {
      status = schemaLifecycle.markDroppingValueIndex(this, indexName, tableName);
    }
    if (status.isOk()) {
      pendingDropIndexName.set(indexName);
      pendingDropTableName.set(tableName);
      pendingDropMutationStart = mutationStart;
      pendingDropType = PENDING_DROP_INDEX;
    }
    finishFailedSchemaChange(status, acquired);
    return status;
  }

  public StatusCode dropTable(CharSequence tableName) {
    if (!registeredTransaction || !RelationalKey.validName(tableName)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (pendingDropType != PENDING_DROP_NONE) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    boolean acquired = !schemaChangeActive;
    StatusCode status = acquireSchemaChange();
    int mutationStart = session.pendingMutationCount();
    if (status.isOk()) {
      status = schemaLifecycle.markDroppingTable(this, tableName);
    }
    if (status.isOk()) {
      pendingDropTableName.set(tableName);
      pendingDropMutationStart = mutationStart;
      pendingDropType = PENDING_DROP_TABLE;
    }
    finishFailedSchemaChange(status, acquired);
    return status;
  }

  public StatusCode renameTable(
      CharSequence currentName,
      CharSequence renamedName) {
    if (!registeredTransaction
        || !RelationalKey.validName(currentName)
        || !RelationalKey.validName(renamedName)
        || sameName(currentName, renamedName)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (pendingDropType != PENDING_DROP_NONE) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    boolean acquired = !schemaChangeActive;
    StatusCode status = acquireSchemaChange();
    if (status.isOk()) {
      status = schemaLifecycle.renameTable(this, currentName, renamedName);
    }
    finishFailedSchemaChange(status, acquired);
    return status;
  }

  public StatusCode renameColumn(
      CharSequence tableName,
      CharSequence currentName,
      CharSequence renamedName) {
    if (!registeredTransaction
        || !RelationalKey.validName(tableName)
        || !RelationalKey.validName(currentName)
        || !RelationalKey.validName(renamedName)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (pendingDropType != PENDING_DROP_NONE) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    boolean acquired = !schemaChangeActive;
    StatusCode status = acquireSchemaChange();
    if (status.isOk()) {
      status = schemaLifecycle.renameColumn(
          this, tableName, currentName, renamedName);
    }
    finishFailedSchemaChange(status, acquired);
    return status;
  }

  public StatusCode renameIndex(
      CharSequence currentName,
      CharSequence renamedName) {
    if (!registeredTransaction
        || !RelationalKey.validName(currentName)
        || !RelationalKey.validName(renamedName)
        || sameName(currentName, renamedName)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (pendingDropType != PENDING_DROP_NONE) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    boolean acquired = !schemaChangeActive;
    StatusCode status = acquireSchemaChange();
    if (status.isOk()) {
      status = schemaLifecycle.renameIndex(this, currentName, renamedName);
    }
    finishFailedSchemaChange(status, acquired);
    return status;
  }

  StatusCode update(TableDefinition table, long key, ByteBuffer row) {
    return rowMutations.update(table, key, row);
  }

  StatusCode delete(TableDefinition table, long key) {
    return rowMutations.delete(table, key);
  }

  public StatusCode fetch(TableDefinition table, long key, HeapRowResult result) {
    return rowMutations.fetch(table, key, result);
  }

  public StatusCode insertLong(
      TableDefinition table,
      long key,
      long value,
      ByteBuffer row) {
    return insertRow(table, key, row);
  }

  public StatusCode insertRow(TableDefinition table, long key, ByteBuffer row) {
    return rowMutations.insertRow(table, key, row);
  }

  public StatusCode updateLong(
      TableDefinition table,
      long key,
      long value,
      ByteBuffer row) {
    return updateRow(table, key, row);
  }

  public StatusCode updateRow(TableDefinition table, long key, ByteBuffer row) {
    return rowMutations.updateRow(table, key, row);
  }

  public StatusCode deleteLong(TableDefinition table, long key) {
    StatusCode status = referentialIntegrity.checkDelete(this, table, key);
    return status.isOk() ? rowMutations.deleteRow(table, key) : status;
  }

  public StatusCode fetchByUniqueValue(
      TableDefinition table,
      long value,
      ValueIndexLookupResult result) {
    return indexLookup.fetch(this, table, value, result);
  }

  public StatusCode fetchByUniqueValue(
      TableDefinition table,
      int column,
      long value,
      ValueIndexLookupResult result) {
    return indexLookup.fetch(this, table, column, value, result);
  }

  public StatusCode beginValueScan(
      TableDefinition table,
      int column,
      RelationalScanCursor cursor) {
    return indexLookup.beginScan(this, table, column, cursor);
  }

  public StatusCode beginValueScan(
      TableDefinition table,
      int column,
      long lowerInclusive,
      long upperExclusive,
      RelationalScanCursor cursor) {
    return indexLookup.beginScan(
        this, table, column, lowerInclusive, upperExclusive, cursor);
  }

  public StatusCode beginExactValueScan(
      TableDefinition table,
      int column,
      long value,
      RelationalScanCursor cursor) {
    return indexLookup.beginExactScan(this, table, column, value, cursor);
  }

  public StatusCode nextValueScan(
      TableDefinition table,
      RelationalScanCursor cursor,
      RelationalScanResult indexResult,
      ValueIndexLookupResult result) {
    return indexLookup.next(
        this, table, cursor, indexResult, result);
  }

  public StatusCode beginNonUniqueValueLookup(
      TableDefinition table,
      int column,
      long value,
      RelationalScanCursor cursor) {
    return indexLookup.beginNonUnique(
        this, table, column, value, cursor);
  }

  public StatusCode nextNonUniqueValueLookup(
      TableDefinition table,
      RelationalScanCursor cursor,
      ValueIndexLookupResult result) {
    return indexLookup.nextNonUnique(this, table, cursor, result);
  }

  public StatusCode beginScan(TableDefinition table, RelationalScanCursor cursor) {
    if (table == null
        || !table.isOwnedBy(schemaGate)
        || cursor == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int dataSpace = RelationalKey.dataSpace(table.tableId());
    StatusCode status = session.beginScan(
        dataSpace,
        Long.MIN_VALUE,
        RelationalKey.auxiliarySpace(table.tableId()),
        Long.MIN_VALUE,
        cursor.indexed());
    return status.isOk() ? cursor.claim(this) : status;
  }

  public StatusCode beginScan(
      TableDefinition table,
      long lowerInclusive,
      long upperExclusive,
      RelationalScanCursor cursor) {
    if (table == null
        || !table.isOwnedBy(schemaGate)
        || upperExclusive <= lowerInclusive
        || cursor == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int dataSpace = RelationalKey.dataSpace(table.tableId());
    StatusCode status = session.beginScan(
        dataSpace,
        lowerInclusive,
        dataSpace,
        upperExclusive,
        cursor.indexed());
    return status.isOk() ? cursor.claim(this) : status;
  }

  public StatusCode beginExactScan(
      TableDefinition table, long key, RelationalScanCursor cursor) {
    if (table == null
        || !table.isOwnedBy(schemaGate)
        || cursor == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int dataSpace = RelationalKey.dataSpace(table.tableId());
    int upperSpace = key == Long.MAX_VALUE ? dataSpace + 1 : dataSpace;
    long upperKey = key == Long.MAX_VALUE ? Long.MIN_VALUE : key + 1;
    StatusCode status = session.beginScan(
        dataSpace, key, upperSpace, upperKey, cursor.indexed());
    return status.isOk() ? cursor.claim(this) : status;
  }

  public StatusCode beginScanFrom(
      TableDefinition table, long lowerInclusive, RelationalScanCursor cursor) {
    if (table == null
        || !table.isOwnedBy(schemaGate)
        || cursor == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int dataSpace = RelationalKey.dataSpace(table.tableId());
    StatusCode status = session.beginScan(
        dataSpace,
        lowerInclusive,
        dataSpace + 1,
        Long.MIN_VALUE,
        cursor.indexed());
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
      result.set(result.indexed().key());
    }
    return status;
  }

  public StatusCode closeScan(RelationalScanCursor cursor) {
    if (cursor == null || !cursor.isOwnedBy(this)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = cursor.exactValueLookup()
        ? StatusCode.OK : session.closeScan(cursor.indexed());
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
        && pendingDropType != PENDING_DROP_NONE
        && session.pendingMutationCount() <= pendingDropMutationStart) {
      clearPendingDrop();
    }
    if (status.isOk()
        && schemaChangeActive
        && session.pendingMutationCount() <= schemaChangeMutationStart) {
      schemaGate.completeSchemaChange(this, false);
      schemaChangeActive = false;
      schemaChangeMutationStart = 0;
    }
    return status;
  }

  public StatusCode releaseSavepoint(IndexedSavepoint savepoint) {
    return session.releaseSavepoint(savepoint);
  }

  public StatusCode cancelLockWait() {
    return session.cancelLockWait();
  }

  public StatusCode commit(TransactionOutcome result) {
    StatusCode status = session.commit(result);
    boolean committed = status.isOk()
        && result.isAvailable()
        && result.state() == TransactionState.COMMITTED;
    releaseTerminalTransaction();
    int cleanupType = pendingDropType;
    if (committed && cleanupType != PENDING_DROP_NONE) {
      pendingDropType = PENDING_DROP_NONE;
      schemaCleanupOutcome.reset();
      status = cleanupType == PENDING_DROP_INDEX
          ? schemaLifecycle.finishDroppingValueIndex(
              this,
              pendingDropIndexName,
              pendingDropTableName,
              schemaCleanupOutcome)
          : schemaLifecycle.finishDroppingTable(
              this,
              pendingDropTableName,
              schemaCleanupOutcome);
    }
    clearPendingDrop();
    completeTerminalSchemaChange(committed);
    return status;
  }

  public StatusCode abort(TransactionOutcome result) {
    StatusCode status = session.abort(result);
    clearPendingDrop();
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
    StatusCode status = schemaGate.beginSchemaChange(this);
    if (status.isOk()) {
      schemaChangeMutationStart = session.pendingMutationCount();
      schemaChangeActive = true;
    }
    return status;
  }

  void releasePersistentSchemaChange() {
    if (schemaChangeActive && !session.transaction().isActiveHandle()) {
      schemaGate.completeSchemaChange(this, false);
      schemaChangeActive = false;
      schemaChangeMutationStart = 0;
    }
  }

  private static boolean sameName(
      CharSequence first,
      CharSequence second) {
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

  private void clearPendingDrop() {
    pendingDropIndexName.reset();
    pendingDropTableName.reset();
    pendingDropMutationStart = 0;
    pendingDropType = PENDING_DROP_NONE;
  }

  StatusCode insertIndexedValue(
      TableDefinition indexTable,
      long value,
      long primaryKey) {
    return secondaryIndexes.insertUnique(indexTable, value, primaryKey);
  }

  StatusCode ensureIndexedValue(
      TableDefinition indexTable,
      long value,
      long primaryKey) {
    return secondaryIndexes.ensureUnique(indexTable, value, primaryKey);
  }

  StatusCode ensureTextIndexedValue(
      TableDefinition indexTable,
      TableDefinition baseTable,
      int column,
      ByteBuffer candidate,
      long primaryKey,
      boolean unique) {
    return secondaryIndexes.ensureText(
        indexTable, baseTable, column, candidate, primaryKey, unique);
  }

  StatusCode insertNonUniqueIndexedValue(
      TableDefinition indexTable,
      long value,
      long primaryKey) {
    return secondaryIndexes.insertNonUnique(indexTable, value, primaryKey);
  }

  StatusCode ensureNonUniqueIndexedValue(
      TableDefinition indexTable,
      long value,
      long primaryKey) {
    return secondaryIndexes.ensureNonUnique(indexTable, value, primaryKey);
  }

  private void releaseTerminalTransaction() {
    if (registeredTransaction && !session.transaction().isActiveHandle()) {
      registeredTransaction = false;
      schemaGate.leaveTransaction();
    }
  }

  private void completeTerminalSchemaChange(boolean committed) {
    if (schemaChangeActive && !session.transaction().isActiveHandle()) {
      schemaGate.completeSchemaChange(this, committed);
      schemaChangeActive = false;
      schemaChangeMutationStart = 0;
    }
  }

}
