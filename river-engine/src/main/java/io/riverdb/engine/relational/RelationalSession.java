package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.schema.cache.SchemaPin;
import io.riverdb.engine.table.IndexedSavepoint;
import io.riverdb.engine.table.IndexedScanResult;
import io.riverdb.storage.heap.HeapRowResult;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
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
  private final RelationalDescriptorSession descriptors;
  private final RelationalDatabaseServices services;
  private final CatalogStatisticsCleanup statisticsCleanup = new CatalogStatisticsCleanup();
  private final SchemaPin descriptorNamespace = new SchemaPin();
  private final TableSchema.ColumnName pendingDropIndexName =
      new TableSchema.ColumnName();
  private final TableSchema.ColumnName pendingDropTableName =
      new TableSchema.ColumnName();
  private final TransactionOutcome schemaCleanupOutcome = new TransactionOutcome();
  private boolean registeredTransaction;
  private boolean schemaChangeActive;
  private boolean nonDescriptorSchemaPublication;
  private int schemaChangeMutationStart;
  private int pendingDropMutationStart;
  private int pendingDropType;
  private boolean closed;

  RelationalSession(
      RelationalSchemaLifecycle lifecycle,
      RelationalSchemaGate gate,
      IndexedTransactionSession indexedSession,
      RelationalDatabaseServices services) {
    schemaLifecycle = lifecycle;
    schemaGate = gate;
    session = indexedSession;
    this.services = services;
    secondaryIndexes = new RelationalSecondaryIndexStore(gate, indexedSession);
    indexLookup = new RelationalIndexLookup(gate, indexedSession);
    catalogDdl = new RelationalCatalogDdl(gate);
    catalogReader = new RelationalCatalogReader(gate, indexedSession);
    referentialIntegrity = new RelationalReferentialIntegrity(gate);
    rowMutations = new RelationalRowMutation(gate, indexedSession, secondaryIndexes);
    descriptors = new RelationalDescriptorSession(
        this, indexedSession, services);
  }

  RelationalSession(
      RelationalSchemaLifecycle lifecycle,
      RelationalSchemaGate gate,
      IndexedTransactionSession indexedSession) {
    this(lifecycle, gate, indexedSession, null);
  }

  /** Catalog-v2 row operations bound to this session and its active transaction. */
  public RelationalDescriptorTableAccess descriptorRows() {
    return descriptors.rows();
  }

  public StatusCode prepareDescriptorTable(
      CharSequence name,
      TableDescriptor descriptor,
      io.riverdb.base.error.StatusDetail detail) {
    return descriptors.prepare(name, descriptor, detail);
  }

  public StatusCode prepareDescriptorSuccessor(
      CharSequence name,
      SchemaPin current,
      TableDescriptor proposed,
      io.riverdb.base.error.StatusDetail detail) {
    return descriptors.prepareSuccessor(name, current, proposed, detail);
  }

  public StatusCode prepareDescriptorSuccessorBuild(
      CharSequence name,
      SchemaPin current,
      TableDescriptor proposed,
      io.riverdb.base.error.StatusDetail detail) {
    return descriptors.prepareSuccessorBuild(name, current, proposed, detail);
  }

  public StatusCode stagePreparedDescriptorSuccessor(
      CharSequence name,
      io.riverdb.base.error.StatusDetail detail) {
    return descriptors.stagePreparedSuccessor(name, detail);
  }

  public StatusCode resolveDescriptor(
      CharSequence name, SchemaPin pin, io.riverdb.base.error.StatusDetail detail) {
    return descriptors.resolve(name, pin, detail);
  }

  StatusCode ensureLegacyNameAbsent(CharSequence name) {
    return catalogDdl.availableName(this, name);
  }

  public StatusCode dropDescriptorTable(
      CharSequence name,
      SchemaPin current,
      io.riverdb.base.error.StatusDetail detail) {
    StatusCode status = descriptors.drop(name, current, detail);
    return status.isOk()
        ? statisticsCleanup.delete(session, (int) current.tableId()) : status;
  }

  public StatusCode renameDescriptorTable(
      CharSequence currentName,
      CharSequence renamedName,
      SchemaPin current,
      io.riverdb.base.error.StatusDetail detail) {
    return descriptors.rename(currentName, renamedName, current, detail);
  }

  public StatusCode checkViewReferences(long tableId) {
    if (!registeredTransaction
        || tableId <= 0
        || tableId > RelationalKey.MAXIMUM_TABLE_ID) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return schemaLifecycle.checkViewReferences(this, (int) tableId);
  }

  int pendingMutationCount() {
    return session.pendingMutationCount();
  }

  StatusCode preflightDescriptorDrop() {
    return session.preflightPendingMutations(
        RelationalDescriptorDropPublications.MUTATION_ROW_LENGTHS, 0, 2);
  }

  StatusCode validateDescriptorNames() {
    return descriptors.validateNames();
  }

  public boolean isTransactionActive() {
    return registeredTransaction;
  }

  /** True while the active transaction requires strict two-phase read protection. */
  public boolean isSerializableTransaction() {
    return registeredTransaction
        && session.transaction().isolationLevel() == IsolationLevel.SERIALIZABLE;
  }

  /** True only while the kernel transaction can still execute or retain statement state. */
  public boolean transactionHandleActive() {
    return session.transaction().isActiveHandle();
  }

  /** Applies opaque attempt, operation, and phase tags to the next or active transaction. */
  public StatusCode configureTransactionDiagnostics(
      long diagnosticTag, long diagnosticStepTag, long metricsEpoch) {
    return session.configureTransactionDiagnostics(
        diagnosticTag, diagnosticStepTag, metricsEpoch);
  }

  public StatusCode updateTransactionDiagnosticStep(long diagnosticStepTag) {
    return session.updateTransactionDiagnosticStep(diagnosticStepTag);
  }

  /** Current global catalog publication token for conservative plan invalidation. */
  public long catalogGeneration() { return schemaGate.version(); }

  public boolean matchesCatalogGeneration(long expected) {
    return schemaGate.matchesVersion(expected);
  }

  public StatusCode begin(IsolationLevel isolationLevel) {
    if (registeredTransaction) return StatusCode.CONFLICT;
    StatusCode cleanup = descriptors.prepareBegin();
    if (!cleanup.isOk()) return cleanup;
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

  public StatusCode completeStatement(boolean deliverResult) {
    return session.completeStatement(deliverResult);
  }

  public StatusCode awaitDurability() { return session.awaitDurability(); }

  public StatusCode resolveTable(CharSequence name, TableDefinition result) {
    return catalogReader.resolveTable(name, result);
  }

  public StatusCode resolveView(
      CharSequence name,
      ViewDefinition result) {
    return catalogReader.resolveView(name, result);
  }

  public StatusCode resolveStatistics(
      TableDefinition table, TableStatistics result) {
    return catalogReader.resolveStatistics(table, result);
  }

  public StatusCode writeStatistics(
      TableDefinition table, TableStatistics statistics) {
    if (!registeredTransaction) return StatusCode.INVALID_EXTERNAL_INPUT;
    return catalogDdl.writeStatistics(this, table, statistics);
  }

  StatusCode reserveCatalogRecords(
      int count, io.riverdb.engine.schema.catalog.CatalogRecordRange result) {
    return services == null
        ? StatusCode.INVALID_EXTERNAL_INPUT
        : services.reserveCatalogRecords(session, count, result);
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
    if (status.isOk()) status = descriptors.ensureNameAbsent(name);
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
    if (status.isOk()) status = descriptors.ensureNameAbsent(name);
    if (status.isOk()) {
      status = catalogDdl.createTable(this, name, schema, result);
    }
    finishFailedSchemaCreation(status, acquired, result);
    return status;
  }

  StatusCode acquireSchemaChange() {
    if (schemaChangeActive) {
      return StatusCode.OK;
    }
    StatusCode status = schemaGate.beginSchemaChange(this);
    if (status.isOk()) {
      schemaChangeMutationStart = session.pendingMutationCount();
      schemaChangeActive = true;
      nonDescriptorSchemaPublication = false;
    }
    return status;
  }

  boolean schemaChangeActive() {
    return schemaChangeActive;
  }

  void cancelSchemaChange() {
    schemaGate.completeSchemaChange(this, false);
    schemaChangeActive = false;
    schemaChangeMutationStart = 0;
    nonDescriptorSchemaPublication = false;
  }

  private void finishFailedSchemaCreation(
      StatusCode status, boolean acquired, TableDefinition result) {
    if (status.isOk()) {
      nonDescriptorSchemaPublication = true;
      return;
    }
    result.reset();
    finishFailedSchemaChange(status, acquired);
  }

  private void finishFailedSchemaChange(StatusCode status, boolean acquired) {
    if (status.isOk()) {
      nonDescriptorSchemaPublication = true;
      return;
    }
    if (!acquired) {
      return;
    }
    schemaGate.completeSchemaChange(this, false);
    schemaChangeActive = false;
    schemaChangeMutationStart = 0;
    nonDescriptorSchemaPublication = false;
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
    if (status.isOk()) status = descriptorNameAvailable(name);
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
        || !RelationalViewLineage.valid(tableIds, tableCount)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (pendingDropType != PENDING_DROP_NONE) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    boolean acquired = !schemaChangeActive;
    StatusCode status = acquireSchemaChange();
    if (status.isOk()) status = descriptorNameAvailable(name);
    if (status.isOk()) {
      status = catalogDdl.createView(
          this, name, query, tableIds, tableCount);
    }
    finishFailedSchemaChange(status, acquired);
    return status;
  }

  private StatusCode descriptorNameAvailable(CharSequence name) {
    StatusCode status = resolveDescriptor(name, descriptorNamespace, null);
    if (status == StatusCode.CONFLICT) return StatusCode.OK;
    if (!status.isOk()) return status;
    StatusCode released = descriptorNamespace.release();
    return released.isOk() ? StatusCode.CONFLICT : released;
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
    long dataSpace = RelationalKey.dataSpace(table.tableId());
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
    long dataSpace = RelationalKey.dataSpace(table.tableId());
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
    long dataSpace = RelationalKey.dataSpace(table.tableId());
    long upperSpace = key == Long.MAX_VALUE ? dataSpace + 1 : dataSpace;
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
    long dataSpace = RelationalKey.dataSpace(table.tableId());
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

  /** Borrows the current uninterrupted successor of one legacy snapshot candidate. */
  public StatusCode lockCurrentRow(
      TableDefinition table, long key, HeapRowResult result) {
    if (table == null || !table.isOwnedBy(schemaGate) || !registeredTransaction
        || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    long space = RelationalKey.dataSpace(table.tableId());
    return session.lockCurrentKey(space, key, result);
  }

  /** Borrows the current row after taking its exclusive key lock before reading it. */
  public StatusCode lockCurrentRowCurrent(
      TableDefinition table, long key, HeapRowResult result) {
    if (table == null || !table.isOwnedBy(schemaGate) || !registeredTransaction
        || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    long space = RelationalKey.dataSpace(table.tableId());
    return session.lockCurrentKeyCurrent(space, key, result);
  }

  /** Retains the current-row claim through transaction completion. */
  public StatusCode retainCurrentRow() {
    return registeredTransaction
        ? session.retainCurrentKey() : StatusCode.INVALID_EXTERNAL_INPUT;
  }

  /** Releases a current-row claim rejected by predicate evaluation or projection. */
  public StatusCode releaseCurrentRow() {
    return registeredTransaction
        ? session.releaseCurrentKey() : StatusCode.INVALID_EXTERNAL_INPUT;
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
    if (status.isOk()) descriptors.rollbackTo(session.pendingMutationCount());
    if (status.isOk()
        && pendingDropType != PENDING_DROP_NONE
        && session.pendingMutationCount() <= pendingDropMutationStart) {
      clearPendingDrop();
    }
    if (status.isOk()
        && schemaChangeActive
        && session.pendingMutationCount() <= schemaChangeMutationStart
        && !descriptors.hasVisible()) {
      schemaGate.completeSchemaChange(this, false);
      schemaChangeActive = false;
      schemaChangeMutationStart = 0;
      nonDescriptorSchemaPublication = false;
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
    StatusCode status = descriptors.closeActiveScan();
    if (!status.isOk()) return status;
    status = session.commit(result);
    status = descriptors.finish(session, result, status);
    boolean committed = descriptors.committed();
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
    if (descriptors.determinate()) {
      completeTerminalSchemaChange(
          descriptors.publishSchemaChange() || nonDescriptorSchemaPublication);
    }
    return status;
  }

  public StatusCode abort(TransactionOutcome result) {
    StatusCode status = descriptors.closeActiveScan();
    if (!status.isOk()) return status;
    status = session.abort(result);
    status = descriptors.finish(session, result, status);
    clearPendingDrop();
    if (descriptors.determinate()) {
      completeTerminalSchemaChange(false);
    }
    releaseTerminalTransaction();
    return status;
  }

  public long visibleCommitSequence() {
    return session.transaction().snapshot().visibleCommitSequence();
  }

  /** True while the kernel can still accept a terminal commit or abort retry. */
  public boolean transactionActive() {
    return session.transactionLifecycleActive();
  }

  public StatusCode close() {
    if (closed) return StatusCode.CLOSED;
    if (registeredTransaction || schemaChangeActive) return StatusCode.CONFLICT;
    StatusCode status = descriptors.closeSession();
    if (status.isOk()) status = session.close();
    if (status.isOk()) closed = true;
    return status;
  }

  IndexedTransactionSession indexedSession() {
    return session;
  }

  StatusCode reserveDescriptorLogicalRowId(
      long objectId, int count,
      io.riverdb.engine.table.IndexedLogicalRowIdReservation result) {
    return descriptors.reserveLogicalRowIds(objectId, count, result);
  }

  boolean authorizesDescriptorPin(SchemaPin pin) {
    return descriptors.authorizes(pin);
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
      nonDescriptorSchemaPublication = false;
    }
    return status;
  }

  void releasePersistentSchemaChange() {
    if (schemaChangeActive && !session.transaction().isActiveHandle()) {
      schemaGate.completeSchemaChange(this, false);
      schemaChangeActive = false;
      schemaChangeMutationStart = 0;
      nonDescriptorSchemaPublication = false;
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
    if (registeredTransaction && !session.transactionLifecycleActive()) {
      registeredTransaction = false;
      schemaGate.leaveTransaction();
    }
  }

  private void completeTerminalSchemaChange(boolean committed) {
    if (schemaChangeActive && !session.transactionLifecycleActive()) {
      schemaGate.completeSchemaChange(this, committed);
      schemaChangeActive = false;
      schemaChangeMutationStart = 0;
      nonDescriptorSchemaPublication = false;
    }
  }

}
