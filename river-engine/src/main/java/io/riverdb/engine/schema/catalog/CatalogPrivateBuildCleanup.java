package io.riverdb.engine.schema.catalog;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.format.catalog.CatalogBuildIntent;

/** Advances one bounded cleanup commit for an unpublished private build. */
final class CatalogPrivateBuildCleanup {
  private static final int CLEANUP_BATCH_RECORDS = 32;
  private final CatalogIntentStore intents;
  private final CatalogBuildRecordCleaner records;
  private final CatalogTupleIndexCleanup indexes;
  private boolean complete;

  CatalogPrivateBuildCleanup(
      CatalogIntentStore intentStore, CatalogDefinitionStore definitions) {
    intents = intentStore;
    records = new CatalogBuildRecordCleaner(definitions);
    indexes = new CatalogTupleIndexCleanup(intentStore);
  }

  StatusCode advance(
      IndexedTransactionSession session, CatalogBuildIntent intent) {
    complete = false;
    StatusCode status = validate(session, intent);
    if (!status.isOk()) return status;
    if (intent.indexCleanupCursor() < intent.nextPhysicalIndex()) {
      status = records.loadPrivateDescriptor(session, intent);
      return status.isOk() ? indexes.advance(
          session, intent, records.privateDescriptor()) : status;
    }
    int total = intent.childCount() + 1;
    int first = intent.cleanupCursor();
    if (first < total) {
      int count = Math.min(CLEANUP_BATCH_RECORDS, total - first);
      status = records.deleteRange(session, intent, first, count);
      return status.isOk()
          ? intents.updateCleanup(session, intent, first + count) : status;
    }
    status = records.deleteBuildingHead(session, intent);
    if (status.isOk()) status = intents.delete(session, intent.objectId());
    complete = status.isOk();
    return status;
  }

  boolean complete() { return complete; }

  private StatusCode validate(
      IndexedTransactionSession session, CatalogBuildIntent intent) {
    if (intent.indexCleanupCursor() > intent.nextPhysicalIndex()) {
      return StatusCode.CORRUPTION;
    }
    return records.requireUnpublished(session, intent);
  }
}
