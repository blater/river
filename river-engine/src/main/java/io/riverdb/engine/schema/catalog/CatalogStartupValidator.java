package io.riverdb.engine.schema.catalog;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.table.IndexedScanCursor;
import io.riverdb.engine.table.IndexedScanResult;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.format.catalog.CatalogKeyspace;
import io.riverdb.format.catalog.CatalogObjectHeadCodec;
import io.riverdb.tx.api.IsolationLevel;

/** Startup cleanup and streaming validation of every published READY catalog head. */
final class CatalogStartupValidator {
  private final CatalogTransactions transactions;
  private final CatalogBuildCleaner cleaner;
  private final CatalogDefinitionStore definitions;
  private final CatalogIndexRootStartupValidation indexes;
  private final CatalogSessionResult opened = new CatalogSessionResult();
  private final IndexedScanCursor cursor = new IndexedScanCursor();
  private final IndexedScanResult scanned = new IndexedScanResult();
  private final TableDescriptor.Result descriptor = new TableDescriptor.Result();
  private final StatusDetail detail = new StatusDetail(128);

  CatalogStartupValidator(
      CatalogTransactions flow,
      CatalogBuildCleaner buildCleaner,
      CatalogDefinitionStore definitionStore) {
    transactions = flow;
    cleaner = buildCleaner;
    definitions = definitionStore;
    indexes = new CatalogIndexRootStartupValidation(definitionStore);
  }

  StatusCode validate() {
    StatusCode status = cleaner.cleanupAll();
    if (!status.isOk()) return status;
    status = transactions.open(opened);
    if (!status.isOk()) return status;
    IndexedTransactionSession session = opened.session();
    status = session.begin(IsolationLevel.REPEATABLE_READ);
    if (status.isOk()) status = cursor.reset();
    if (status.isOk()) status = session.beginScan(CatalogKeyspace.OBJECT_HEAD_SPACE,
        Long.MIN_VALUE, CatalogKeyspace.DEFINITION_SPACE, Long.MIN_VALUE, cursor);
    while (status.isOk()) {
      status = session.nextScan(cursor, scanned);
      if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
        break;
      }
      if (status.isOk()) status = validateHead(session);
    }
    if (cursor.isActive()) {
      StatusCode closed = session.closeScan(cursor);
      if (status.isOk()) status = closed;
    }
    if (status.isOk()) status = indexes.validateRegistry(session);
    return transactions.finish(session, status, false);
  }

  private StatusCode validateHead(IndexedTransactionSession session) {
    if (scanned.keySpace() != CatalogKeyspace.OBJECT_HEAD_SPACE || scanned.key() <= 0) {
      return StatusCode.CORRUPTION;
    }
    StatusCode status = definitions.readAnyHead(session, scanned.key());
    if (status.isOk()
        && definitions.headState() == CatalogObjectHeadCodec.STATE_TOMBSTONE) {
      descriptor.reset();
      return StatusCode.OK;
    }
    if (status.isOk() && definitions.headState() != CatalogObjectHeadCodec.STATE_READY) {
      status = StatusCode.CORRUPTION;
    }
    if (status.isOk()) status = definitions.assembleCurrent(
        session, scanned.key(), descriptor, detail);
    if (status.isOk()) status = indexes.validateTable(session, descriptor.value());
    descriptor.reset();
    return status == StatusCode.CONFLICT ? StatusCode.CORRUPTION : status;
  }
}
