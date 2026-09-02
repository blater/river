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

/** Loads one exact durable historical layout generation and retains it in the schema cache. */
final class CatalogHistoricalTableOpener {
  private final SchemaCache cache;
  private final CatalogTransactions transactions;
  private final CatalogDefinitionStore definitions;
  private final CatalogSessionResult opened = new CatalogSessionResult();
  private final TableDescriptor.Result descriptor = new TableDescriptor.Result();
  private final SchemaAdmission admission = new SchemaAdmission();

  CatalogHistoricalTableOpener(
      SchemaCache schemaCache, CatalogTransactions flow, CatalogDefinitionStore store) {
    cache = schemaCache;
    transactions = flow;
    definitions = store;
  }

  StatusCode open(
      long objectId, long rowLayoutId, long generation,
      SchemaPin pin, StatusDetail detail) {
    if (detail != null) detail.reset();
    descriptor.reset();
    if (!CatalogKeyspace.validObjectHead(objectId) || rowLayoutId <= 0 || generation < 0
        || pin == null || pin.isActive()) return fail(detail, StatusCode.INVALID_EXTERNAL_INPUT);
    StatusCode status = transactions.open(opened);
    if (!status.isOk()) return fail(detail, status);
    IndexedTransactionSession session = opened.session();
    status = session.begin(IsolationLevel.REPEATABLE_READ);
    if (status.isOk()) status = definitions.loadHistorical(
        session, objectId, rowLayoutId, generation, descriptor, detail);
    if (!status.isOk()) return fail(detail, transactions.finish(session, status, false));
    TableDescriptor value = descriptor.value();
    status = cache.lookupCurrent(
        objectId, value.schemaId(), rowLayoutId, value.catalogGeneration(), pin);
    if (status.isOk()) return finishCached(session, pin, detail);
    if (status == StatusCode.CONFLICT) status = cache.reserveRetained(value, admission);
    StatusCode terminal = transactions.finish(session, status, false);
    if (!terminal.isOk()) return cancel(terminal, detail);
    status = admission.publish(value, pin);
    return status.isOk() ? succeed(detail) : cancel(status, detail);
  }

  private StatusCode finishCached(
      IndexedTransactionSession session, SchemaPin pin, StatusDetail detail) {
    StatusCode status = transactions.finish(session, StatusCode.OK, false);
    if (!status.isOk()) pin.release();
    return status.isOk() ? succeed(detail) : fail(detail, status);
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
