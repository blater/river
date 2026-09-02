package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.table.IndexedTransactionSession;

/** Removes a statistics header and its exact referenced immutable child generation. */
final class CatalogStatisticsCleanup {
  private final CatalogStatisticsHeader header = new CatalogStatisticsHeader();

  StatusCode delete(IndexedTransactionSession session, int tableId) {
    StatusCode status = header.read(session, tableId);
    if (status == StatusCode.CONFLICT) return StatusCode.OK;
    if (status.isOk()) {
      status = CatalogStatisticsWriter.deleteChildren(session, header.manifest());
    }
    return status.isOk()
        ? session.delete(
            RelationalKey.CATALOG_SEQUENCE_SPACE,
            RelationalKey.tableStatisticsKey(tableId)) : status;
  }
}
