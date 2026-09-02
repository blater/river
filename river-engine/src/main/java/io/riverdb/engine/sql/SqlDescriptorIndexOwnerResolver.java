package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.engine.relational.CatalogObjectCursor;
import io.riverdb.engine.relational.CatalogObjectResult;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.schema.cache.SchemaPin;

/** Finds the unique descriptor table owning one named key without allocating per catalog row. */
final class SqlDescriptorIndexOwnerResolver {
  private final CatalogObjectCursor cursor = new CatalogObjectCursor();
  private final CatalogObjectResult object = new CatalogObjectResult();
  private final SqlDescriptorIndexOwnerSelection selection =
      new SqlDescriptorIndexOwnerSelection();

  StatusCode resolve(
      RelationalSession session,
      CharSequence indexName,
      CharSequence renamedName,
      StatusDetail detail) {
    StatusCode status = selection.reset();
    if (!status.isOk()) return status;
    status = cursor.reset();
    if (status.isOk()) status = session.beginCatalogObjectScan(cursor);
    boolean active = status.isOk();
    while (status.isOk()) {
      status = session.nextCatalogObject(cursor, object);
      if (!status.isOk() || !object.isAvailable()) break;
      if (object.type() != CatalogObjectResult.TABLE) continue;
      status = selection.inspect(
          session, object.name(), indexName, renamedName, detail);
    }
    if (active) {
      StatusCode closed = session.closeCatalogObjectScan(cursor);
      if (status.isOk()) status = closed;
    }
    return selection.finish(status);
  }

  SchemaPin owner() { return selection.owner(); }
  CharSequence tableName() { return selection.tableName(); }
  boolean legacyIndex() { return selection.legacyIndex(); }

  StatusCode release() {
    return selection.release();
  }
}
