package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;

/** Removes catalog index records, the table record, and statistics atomically. */
final class RelationalCatalogCleanup {
  private RelationalCatalogCleanup() { }

  static StatusCode remove(RelationalPhysicalCleanup cleanup, RelationalSession session,
      TableDefinition table, CharSequence tableName, TransactionOutcome outcome) {
    StatusCode status = session.begin(IsolationLevel.SERIALIZABLE);
    boolean scanActive = false;
    if (status.isOk()) {
      status = session.indexedSession().beginScan(RelationalKey.CATALOG_OBJECT_SPACE,
          Long.MIN_VALUE, RelationalKey.CATALOG_SEQUENCE_SPACE, Long.MIN_VALUE,
          cleanup.catalogCursor);
      scanActive = status.isOk();
    }
    int count = status.isOk() ? cleanup.collectIndexCatalogKeys(session, table) : 0;
    if (status.isOk()) status = cleanup.collectStatus;
    if (scanActive) {
      StatusCode close = session.indexedSession().closeScan(cleanup.catalogCursor);
      if (status.isOk()) status = close;
    }
    cleanup.catalogCursor.reset();
    for (int index = 0; status.isOk() && index < count; index++) {
      status = session.indexedSession().delete(RelationalKey.CATALOG_OBJECT_SPACE,
          cleanup.indexCatalogKeys[index]);
      cleanup.indexCatalogKeys[index] = 0;
    }
    if (status.isOk()) status = RelationalKey.catalogTableKey(tableName, cleanup.catalogKey);
    if (status.isOk()) status = session.indexedSession().delete(
        cleanup.catalogKey.space(), cleanup.catalogKey.key());
    if (status.isOk()) status = cleanup.deleteStatistics(session, table.tableId());
    return RelationalPhysicalCleanup.finishTransaction(session, outcome, status);
  }
}
