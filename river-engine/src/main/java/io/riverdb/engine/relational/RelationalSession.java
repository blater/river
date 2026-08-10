package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.table.IndexedTransactionSession;
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

  RelationalSession(RelationalDatabase owner, IndexedTransactionSession indexedSession) {
    database = owner;
    session = indexedSession;
  }

  public StatusCode begin(IsolationLevel isolationLevel) {
    return session.begin(isolationLevel);
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

  public StatusCode commit(TransactionOutcome result) {
    return session.commit(result);
  }

  public StatusCode abort(TransactionOutcome result) {
    return session.abort(result);
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
}
