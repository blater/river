package io.riverdb.engine.schema.catalog;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.format.catalog.CatalogBuildIntent;
import io.riverdb.format.catalog.CatalogBuildIntentCodec;

/** Verifies an atomically published build before deleting its residual intent. */
final class CatalogPublishedBuildReconciliation {
  private final CatalogIntentStore intents;
  private final CatalogBuildRecordCleaner records;
  private final CatalogTupleIndexCleanup indexes;

  CatalogPublishedBuildReconciliation(
      CatalogIntentStore intentStore, CatalogDefinitionStore definitions) {
    intents = intentStore;
    records = new CatalogBuildRecordCleaner(definitions);
    indexes = new CatalogTupleIndexCleanup(intentStore);
  }

  StatusCode reconcile(
      IndexedTransactionSession session, CatalogBuildIntent intent) {
    StatusCode status = intent.state() == CatalogBuildIntentCodec.STATE_READY
        ? records.validateReady(session, intent)
        : records.validatePublished(session, intent);
    if (status.isOk()) status = indexes.validatePublished(
        session, intent, records.privateDescriptor());
    return status.isOk() ? intents.delete(session, intent.objectId()) : status;
  }
}
