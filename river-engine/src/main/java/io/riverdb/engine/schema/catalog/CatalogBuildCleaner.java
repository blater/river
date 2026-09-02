package io.riverdb.engine.schema.catalog;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.table.IndexedScanCursor;
import io.riverdb.engine.table.IndexedScanResult;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.format.catalog.CatalogBuildIntent;
import io.riverdb.format.catalog.CatalogKeyspace;
import io.riverdb.tx.api.IsolationLevel;

/** Bounded restart cleanup for committed records unreachable from an object head. */
final class CatalogBuildCleaner {
  private final CatalogTransactions transactions;
  private final CatalogIntentStore intents;
  private final CatalogPublishedBuildReconciliation published;
  private final CatalogPrivateBuildCleanup privateBuild;
  private final CatalogSessionResult opened = new CatalogSessionResult();
  private final IndexedScanCursor cursor = new IndexedScanCursor();
  private final IndexedScanResult scanned = new IndexedScanResult();
  private long firstObjectId;

  CatalogBuildCleaner(
      CatalogTransactions flow, CatalogIntentStore intentStore,
      CatalogDefinitionStore definitionStore) {
    transactions = flow;
    intents = intentStore;
    published = new CatalogPublishedBuildReconciliation(
        intentStore, definitionStore);
    privateBuild = new CatalogPrivateBuildCleanup(
        intentStore, definitionStore);
  }

  StatusCode cleanupAll() {
    while (true) {
      StatusCode status = firstIntent();
      if (!status.isOk() || firstObjectId == 0) return status;
      status = cleanup(firstObjectId);
      if (!status.isOk()) return status;
    }
  }

  StatusCode cleanup(long objectId) {
    while (true) {
      StatusCode status = transactions.open(opened);
      if (!status.isOk()) return status;
      IndexedTransactionSession session = opened.session();
      status = session.begin(IsolationLevel.SERIALIZABLE);
      if (status.isOk()) status = intents.read(session, objectId);
      if (!status.isOk()) return transactions.finish(session, status, false);
      CatalogBuildIntent intent = intents.value();
      status = published.reconcile(session, intent);
      if (status.isOk()) {
        return transactions.finish(session, status, true);
      }
      if (status != StatusCode.CONFLICT) {
        return transactions.finish(session, status, false);
      }
      status = privateBuild.advance(session, intent);
      status = transactions.finish(session, status, true);
      if (!status.isOk() || privateBuild.complete()) return status;
    }
  }

  private StatusCode firstIntent() {
    firstObjectId = 0;
    StatusCode status = transactions.open(opened);
    if (!status.isOk()) return status;
    IndexedTransactionSession session = opened.session();
    status = session.begin(IsolationLevel.REPEATABLE_READ);
    if (status.isOk()) status = cursor.reset();
    if (status.isOk()) status = session.beginScan(CatalogKeyspace.BUILD_INTENT_SPACE,
        Long.MIN_VALUE, CatalogKeyspace.OBJECT_HEAD_SPACE, Long.MIN_VALUE, cursor);
    if (status.isOk()) status = session.nextScan(cursor, scanned);
    if (status == StatusCode.CONFLICT) {
      status = StatusCode.OK;
    } else if (status.isOk()
        && (scanned.keySpace() != CatalogKeyspace.BUILD_INTENT_SPACE
            || scanned.key() <= 0)) {
      status = StatusCode.CORRUPTION;
    } else if (status.isOk()) {
      firstObjectId = scanned.key();
    }
    if (cursor.isActive()) {
      StatusCode closed = session.closeScan(cursor);
      if (status.isOk()) status = closed;
    }
    return transactions.finish(session, status, false);
  }

}
