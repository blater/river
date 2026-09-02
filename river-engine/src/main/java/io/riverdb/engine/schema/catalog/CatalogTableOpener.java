package io.riverdb.engine.schema.catalog;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.schema.cache.SchemaAdmission;
import io.riverdb.engine.schema.cache.SchemaCache;
import io.riverdb.engine.schema.cache.SchemaPin;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.format.catalog.CatalogKeyspace;
import io.riverdb.tx.api.IsolationLevel;

/** Loads and atomically pins the current descriptor reached by one durable object head. */
final class CatalogTableOpener {
  private final SchemaCache cache;
  private final CatalogTransactions transactions;
  private final CatalogDefinitionStore definitions;
  private final TableDescriptor.Result descriptorResult = new TableDescriptor.Result();
  private final SchemaAdmission admission = new SchemaAdmission();
  private final CatalogSessionResult opened = new CatalogSessionResult();

  CatalogTableOpener(
      SchemaCache schemaCache,
      CatalogTransactions transactionFlow,
      CatalogDefinitionStore definitionStore) {
    cache = schemaCache;
    transactions = transactionFlow;
    definitions = definitionStore;
  }

  StatusCode open(long objectId, SchemaPin pin, StatusDetail detail) {
    if (detail != null) detail.reset();
    descriptorResult.reset();
    if (!CatalogKeyspace.validObjectHead(objectId) || pin == null || pin.isActive()) {
      return fail(detail, StatusCode.INVALID_EXTERNAL_INPUT);
    }
    StatusCode status = transactions.open(opened);
    if (!status.isOk()) return fail(detail, status);
    IndexedTransactionSession session = opened.session();
    status = session.begin(IsolationLevel.REPEATABLE_READ);
    if (status.isOk()) status = definitions.readHead(session, objectId);
    if (status.isOk()) status = definitions.readCurrentManifest(session, objectId);
    if (!status.isOk()) return fail(detail, transactions.finish(session, status, false));
    status = cache.lookupCurrent(objectId, definitions.headSchemaId(),
        definitions.currentRowLayoutId(),
        definitions.headGeneration(), pin);
    if (status.isOk()) return finishCached(session, pin, detail);
    if (status != StatusCode.CONFLICT) {
      return fail(detail, transactions.finish(session, status, false));
    }
    status = definitions.assembleCurrent(session, objectId, descriptorResult, detail);
    if (status.isOk()) status = cache.reserveCurrent(
        descriptorResult.value(), definitions.headGeneration(), admission);
    StatusCode terminal = transactions.finish(session, status, false);
    if (!terminal.isOk()) return cancel(terminal, detail);
    status = admission.publish(descriptorResult.value(), pin);
    return status.isOk() ? succeed(detail) : cancel(status, detail);
  }

  private StatusCode finishCached(
      IndexedTransactionSession session, SchemaPin pin, StatusDetail detail) {
    StatusCode terminal = transactions.finish(session, StatusCode.OK, false);
    if (!terminal.isOk()) pin.release();
    return terminal.isOk() ? succeed(detail) : fail(detail, terminal);
  }

  private StatusCode cancel(StatusCode status, StatusDetail detail) {
    if (admission.isActive()) {
      StatusCode cancelled = admission.cancel();
      if (status.isOk()) status = cancelled;
    }
    return fail(detail, status);
  }

  private static StatusCode succeed(StatusDetail detail) {
    if (detail != null) detail.set(StatusCode.OK);
    return StatusCode.OK;
  }

  private static StatusCode fail(StatusDetail detail, StatusCode status) {
    if (detail != null && detail.code() == StatusCode.OK) detail.set(status);
    return status;
  }
}
