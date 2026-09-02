package io.riverdb.engine.schema.catalog;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.schema.cache.SchemaCache;
import io.riverdb.engine.schema.cache.SchemaPin;
import io.riverdb.engine.table.IndexedTransactionSession;

/** Validates and stages the sole durable visibility change for a table drop. */
final class CatalogTableDrop {
  private final SchemaCache cache;
  private final CatalogObjectHeadStore heads;
  private final CatalogDefinitionStore definitions;

  CatalogTableDrop(
      SchemaCache schemaCache,
      CatalogObjectHeadStore headStore,
      CatalogDefinitionStore definitionStore) {
    cache = schemaCache;
    heads = headStore;
    definitions = definitionStore;
  }

  StatusCode prepare(SchemaPin current, IndexedTransactionSession session) {
    if (current == null || !current.isActive() || !cache.owns(current)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    long generation = current.descriptor().catalogGeneration();
    if (generation == Long.MAX_VALUE) return StatusCode.RESOURCE_EXHAUSTED;
    StatusCode status = definitions.readAnyHead(session, current.tableId());
    if (status.isOk()
        && (definitions.headState()
                != io.riverdb.format.catalog.CatalogObjectHeadCodec.STATE_READY
            || definitions.headSchemaId() != current.schemaId()
            || definitions.headGeneration() != generation)) {
      status = StatusCode.CONFLICT;
    }
    if (status.isOk() && current.isPublished()) {
      status = definitions.readCurrentManifest(session, current.tableId());
    }
    if (status.isOk() && current.isPublished()
        && definitions.currentRowLayoutId() != current.rowLayoutId()) {
      status = StatusCode.CORRUPTION;
    }
    return status.isOk()
        ? heads.updateTombstone(session, current.tableId(), generation + 1) : status;
  }
}
