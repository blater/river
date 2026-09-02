package io.riverdb.engine.schema.catalog;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.engine.EmbeddedDatabase;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.schema.cache.SchemaCache;
import io.riverdb.engine.schema.cache.SchemaPin;
import io.riverdb.engine.table.IndexedTransactionSession;

/** Coordinates private successor preparation, optional backfill, and publication. */
final class CatalogSuccessorLifecycle {
  private final EmbeddedDatabase embedded;
  private final SchemaCache cache;
  private final CatalogTableSuccessor successor;

  CatalogSuccessorLifecycle(
      EmbeddedDatabase database,
      SchemaCache schemaCache,
      CatalogTableSuccessor tableSuccessor) {
    embedded = database;
    cache = schemaCache;
    successor = tableSuccessor;
  }

  StatusCode prepare(
      SchemaPin current,
      TableDescriptor proposed,
      IndexedTransactionSession session,
      CatalogPreparedTable prepared,
      StatusDetail detail,
      boolean stagePublication) {
    if (detail != null) detail.reset();
    StatusCode status = CatalogPublicationAdmission.validate(embedded, session, detail);
    if (!status.isOk()) return status;
    boolean owned = cache.owns(current);
    if (!owned || !current.isPublished()) {
      status = owned ? StatusCode.CONFLICT : StatusCode.INVALID_EXTERNAL_INPUT;
      if (detail != null) detail.set(status);
      return status;
    }
    return stagePublication
        ? successor.prepare(current.descriptor(), proposed, session, prepared, detail)
        : successor.prepareBuild(current.descriptor(), proposed, session, prepared, detail);
  }

  StatusCode stage(
      IndexedTransactionSession session,
      CatalogPreparedTable prepared,
      StatusDetail detail) {
    StatusCode status = CatalogPublicationAdmission.validate(embedded, session, detail);
    return status.isOk() ? successor.stagePrepared(session, prepared, detail) : status;
  }
}
