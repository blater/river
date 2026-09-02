package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.engine.EmbeddedDatabase;
import io.riverdb.engine.runtime.SqlDatabaseRuntime;
import io.riverdb.engine.runtime.SqlRuntimeLeaseResult;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.schema.cache.SchemaCache;
import io.riverdb.engine.schema.cache.SchemaPin;
import io.riverdb.engine.schema.catalog.CatalogTableLifecycle;
import io.riverdb.engine.schema.catalog.CatalogPreparedTable;
import io.riverdb.engine.schema.catalog.CatalogRecordRange;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.tx.api.TransactionState;

/** Cohesive lifecycle for SQL runtime, schema cache, and catalog-v2 descriptor services. */
public final class RelationalDatabaseServices {
  private final EmbeddedDatabase embedded;
  private final SqlDatabaseRuntime runtime;
  private final SchemaCache cache;
  private final CatalogTableLifecycle catalog;
  private final RelationalDescriptorCatalog descriptors;
  private boolean closePrepared;
  private boolean catalogClosed;
  private boolean runtimeClosed;
  private boolean embeddedClosed;

  RelationalDatabaseServices(
      EmbeddedDatabase embedded, SqlDatabaseRuntime sqlRuntime, SchemaCache schemaCache) {
    this.embedded = embedded;
    runtime = sqlRuntime;
    cache = schemaCache;
    catalog = new CatalogTableLifecycle(embedded, schemaCache);
    descriptors = new RelationalDescriptorCatalog(this);
  }

  public long schemaCacheBudgetBytes() {
    return cache.budgetBytes();
  }

  public long schemaCacheMaximumBytes() {
    return cache.maximumBytes();
  }

  synchronized boolean isClosing() {
    return closePrepared;
  }

  public RelationalDescriptorCatalog descriptors() {
    return descriptors;
  }

  RelationalDescriptorIndexBuildSession newDescriptorIndexBuildSession() {
    return new RelationalDescriptorIndexBuildSession(embedded);
  }

  synchronized StatusCode create(
      TableDescriptor table, SchemaPin pin, StatusDetail detail) {
    return closePrepared ? StatusCode.CLOSED : catalog.create(table, pin, detail);
  }

  synchronized StatusCode open(long objectId, SchemaPin pin, StatusDetail detail) {
    return closePrepared ? StatusCode.CLOSED : catalog.open(objectId, pin, detail);
  }

  synchronized StatusCode openRetained(
      long objectId, long rowLayoutId, SchemaPin pin, StatusDetail detail) {
    return closePrepared
        ? StatusCode.CLOSED : catalog.openRetained(objectId, rowLayoutId, pin, detail);
  }

  synchronized StatusCode prepareDescriptor(
      TableDescriptor table,
      IndexedTransactionSession session,
      CatalogPreparedTable prepared,
      StatusDetail detail) {
    return closePrepared
        ? StatusCode.CLOSED : catalog.prepare(table, session, prepared, detail);
  }

  synchronized StatusCode prepareDescriptorSuccessor(
      SchemaPin current,
      TableDescriptor proposed,
      IndexedTransactionSession session,
      CatalogPreparedTable prepared,
      StatusDetail detail) {
    return closePrepared ? StatusCode.CLOSED
        : catalog.prepareSuccessor(current, proposed, session, prepared, detail);
  }

  synchronized StatusCode prepareDescriptorSuccessorBuild(
      SchemaPin current,
      TableDescriptor proposed,
      IndexedTransactionSession session,
      CatalogPreparedTable prepared,
      StatusDetail detail) {
    return closePrepared ? StatusCode.CLOSED
        : catalog.prepareSuccessorBuild(current, proposed, session, prepared, detail);
  }

  synchronized StatusCode stagePreparedDescriptorSuccessor(
      IndexedTransactionSession session,
      CatalogPreparedTable prepared,
      StatusDetail detail) {
    return closePrepared ? StatusCode.CLOSED
        : catalog.stagePreparedSuccessor(session, prepared, detail);
  }

  boolean owns(SchemaPin pin) {
    return cache.owns(pin);
  }

  synchronized StatusCode finishDescriptor(
      CatalogPreparedTable prepared, TransactionState outcome) {
    return catalog.finish(prepared, outcome);
  }

  synchronized StatusCode prepareDescriptorDrop(
      SchemaPin current,
      IndexedTransactionSession session,
      StatusDetail detail) {
    return closePrepared
        ? StatusCode.CLOSED : catalog.prepareDrop(current, session, detail);
  }

  synchronized StatusCode reserveCatalogRecords(
      IndexedTransactionSession session, int count, CatalogRecordRange result) {
    return closePrepared
        ? StatusCode.CLOSED : catalog.reserveRecords(session, count, result);
  }

  StatusCode initializeCatalog() {
    return catalog.initialize();
  }

  StatusCode validateCatalog() {
    return catalog.validate();
  }

  public synchronized StatusCode acquireRuntime(SqlRuntimeLeaseResult result) {
    return closePrepared ? StatusCode.CLOSED : runtime.acquire(result);
  }

  synchronized StatusCode close(EmbeddedDatabase embedded) {
    if (runtimeClosed && embeddedClosed) return StatusCode.CLOSED;
    if (!closePrepared) {
      StatusCode status = runtime.prepareClose();
      if (!status.isOk()) return status;
      closePrepared = true;
    }
    StatusCode first = closeCatalog();
    first = firstFailure(first, closeRuntime());
    return firstFailure(first, closeEmbedded(embedded));
  }

  synchronized StatusCode closeAfterOpenFailure() {
    StatusCode first = closeCatalog();
    StatusCode runtimeStatus = runtime.prepareClose();
    if (runtimeStatus.isOk()) runtimeStatus = runtime.completeClose();
    first = firstFailure(first, runtimeStatus);
    first = firstFailure(first, embedded.closeAfterOpenFailure());
    closePrepared = catalogClosed = runtimeClosed = embeddedClosed = true;
    return first;
  }

  private StatusCode closeCatalog() {
    if (catalogClosed) return StatusCode.OK;
    StatusCode status = catalog.close();
    if (status.isOk()) catalogClosed = true;
    return status;
  }

  private StatusCode closeRuntime() {
    if (runtimeClosed) return StatusCode.OK;
    StatusCode status = runtime.completeClose();
    if (status.isOk()) runtimeClosed = true;
    return status;
  }

  private StatusCode closeEmbedded(EmbeddedDatabase embedded) {
    if (embeddedClosed) return StatusCode.OK;
    StatusCode status = embedded.close();
    if (status.isOk()) embeddedClosed = true;
    return status;
  }

  private static StatusCode firstFailure(StatusCode first, StatusCode next) {
    return first.isOk() ? next : first;
  }
}
