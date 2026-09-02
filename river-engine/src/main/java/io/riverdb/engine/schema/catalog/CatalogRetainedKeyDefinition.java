package io.riverdb.engine.schema.catalog;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.table.IndexedScanCursor;
import io.riverdb.engine.table.IndexedScanResult;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.format.catalog.CatalogKeyspace;

/** Finds the retained descriptor generation that owns one durable tuple-index root. */
final class CatalogRetainedKeyDefinition {
  private final CatalogDefinitionStore definitions;
  private final IndexedScanCursor cursor = new IndexedScanCursor();
  private final IndexedScanResult row = new IndexedScanResult();

  CatalogRetainedKeyDefinition(CatalogDefinitionStore store) {
    definitions = store;
  }

  StatusCode load(
      IndexedTransactionSession session,
      long objectId,
      long keyId,
      TableDescriptor.Result result,
      StatusDetail detail) {
    if (!valid(session, objectId, keyId, result)) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    StatusCode status = cursor.reset();
    if (status.isOk()) status = session.beginScan(
        CatalogKeyspace.DEFINITION_SPACE, Long.MIN_VALUE,
        CatalogKeyspace.DEFINITION_SPACE, Long.MAX_VALUE, cursor);
    boolean found = false;
    while (status.isOk()) {
      status = session.nextScan(cursor, row);
      if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
        break;
      }
      if (!status.isOk()) break;
      status = definitions.inspectManifest(row.row(), row.key());
      if (!status.isOk()) break;
      if (definitions.inspectedManifestRecordId() == 0
          || definitions.inspectedObjectId() != objectId) continue;
      status = definitions.assembleInspected(session, result, detail);
      if (!status.isOk()) break;
      found = contains(result.value(), keyId);
      if (found) break;
      result.reset();
    }
    if (cursor.isActive()) {
      StatusCode closed = session.closeScan(cursor);
      if (status.isOk()) status = closed;
    }
    return status.isOk() && !found ? StatusCode.CONFLICT : status;
  }

  private static boolean valid(
      IndexedTransactionSession session,
      long objectId,
      long keyId,
      TableDescriptor.Result result) {
    return session != null && CatalogKeyspace.validObjectHead(objectId)
        && CatalogKeyspace.validKeyId(keyId) && result != null;
  }

  private static boolean contains(TableDescriptor table, long keyId) {
    if (table.primaryKey() != null && table.primaryKey().keyId() == keyId) return true;
    for (int index = 0; index < table.secondaryKeyCount(); index++) {
      if (table.secondaryKeyAt(index).keyId() == keyId) return true;
    }
    return false;
  }
}
