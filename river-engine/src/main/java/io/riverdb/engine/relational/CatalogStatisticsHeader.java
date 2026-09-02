package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.format.catalog.CatalogDefinitionManifest;
import io.riverdb.format.catalog.CatalogDefinitionManifestCodec;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

/** Reusable point reader for the authoritative statistics manifest/header row. */
final class CatalogStatisticsHeader {
  private final CatalogDefinitionManifest manifest = new CatalogDefinitionManifest();
  private final HeapRowResult row = new HeapRowResult();
  private final ByteBuffer bytes =
      ByteBuffer.allocateDirect(CatalogDefinitionManifestCodec.BYTES);
  private final CRC32C checksum = new CRC32C();

  StatusCode read(IndexedTransactionSession session, int tableId) {
    manifest.reset();
    if (session == null || tableId <= 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = session.fetchByKey(
        RelationalKey.CATALOG_SEQUENCE_SPACE,
        RelationalKey.tableStatisticsKey(tableId), row);
    if (!status.isOk()) return status;
    if (row.length() != CatalogDefinitionManifestCodec.BYTES) {
      return StatusCode.CORRUPTION;
    }
    bytes.clear();
    status = row.copyTo(bytes);
    bytes.flip();
    if (status.isOk()) {
      status = CatalogDefinitionManifestCodec.decode(bytes, 0, manifest, checksum);
    }
    return status.isOk() && manifest.objectId() != tableId
        ? StatusCode.CORRUPTION : status;
  }

  CatalogDefinitionManifest manifest() { return manifest; }
}
