package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.engine.table.IndexedSavepoint;
import io.riverdb.storage.heap.HeapRowResult;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import java.nio.ByteBuffer;

/** Transaction session over catalog-resolved logical tables in one physical keyspace. */
public final class RelationalSession {
  private final RelationalDatabase database;
  private final IndexedTransactionSession session;
  private final RelationalKey.LongKeyResult physicalKey = new RelationalKey.LongKeyResult();
  private final HeapRowResult catalogRow = new HeapRowResult();
  private final ByteBuffer catalogScratch = ByteBuffer.allocateDirect(CatalogRecord.MAXIMUM_BYTES);
  private final TableDefinition valueIndexTable = new TableDefinition();
  private final HeapRowResult indexedKeyRow = new HeapRowResult();
  private final ByteBuffer valueScratch = ByteBuffer.allocateDirect(Long.BYTES);
  private final ByteBuffer indexRow = ByteBuffer.allocateDirect(Long.BYTES);
  private boolean registeredTransaction;

  RelationalSession(RelationalDatabase owner, IndexedTransactionSession indexedSession) {
    database = owner;
    session = indexedSession;
  }

  public StatusCode begin(IsolationLevel isolationLevel) {
    if (registeredTransaction) {
      return StatusCode.CONFLICT;
    }
    StatusCode status = database.enterTransaction();
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

  public StatusCode insert(TableDefinition table, long key, ByteBuffer row) {
    StatusCode status = resolveKey(table, key);
    return status.isOk() ? session.insert(physicalKey.key(), row) : status;
  }

  public StatusCode update(TableDefinition table, long key, ByteBuffer row) {
    StatusCode status = resolveKey(table, key);
    return status.isOk() ? session.update(physicalKey.key(), row) : status;
  }

  public StatusCode delete(TableDefinition table, long key) {
    StatusCode status = resolveKey(table, key);
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
      status = insert(valueIndexTable, value, indexRow);
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
      status = delete(valueIndexTable, previousValue);
      if (status.isOk()) {
        encodeLong(indexRow, key);
        status = insert(valueIndexTable, value, indexRow);
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
      status = delete(valueIndexTable, previousValue);
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
    StatusCode status = fetch(valueIndexTable, value, indexedKeyRow);
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
    return session.rollbackToSavepoint(savepoint);
  }

  public StatusCode releaseSavepoint(IndexedSavepoint savepoint) {
    return session.releaseSavepoint(savepoint);
  }

  public StatusCode commit(TransactionOutcome result) {
    StatusCode status = session.commit(result);
    releaseTerminalTransaction();
    return status;
  }

  public StatusCode abort(TransactionOutcome result) {
    StatusCode status = session.abort(result);
    releaseTerminalTransaction();
    return status;
  }

  public long visibleCommitSequence() {
    return session.transaction().snapshot().visibleCommitSequence();
  }

  IndexedTransactionSession indexedSession() {
    return session;
  }

  private StatusCode resolveKey(TableDefinition table, long key) {
    if (table == null || !table.isOwnedBy(database)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return RelationalKey.tableRowKey(table.tableId(), key, physicalKey);
  }

  private void prepareValueIndex(TableDefinition table) {
    valueIndexTable.set(database, table.uniqueValueIndexTableId(), 0);
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

  private void releaseTerminalTransaction() {
    if (registeredTransaction && !session.transaction().isActiveHandle()) {
      registeredTransaction = false;
      database.leaveTransaction();
    }
  }
}
