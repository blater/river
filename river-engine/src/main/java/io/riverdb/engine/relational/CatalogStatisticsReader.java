package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.format.catalog.CatalogAssemblyValidator;
import io.riverdb.format.catalog.CatalogDefinitionManifest;
import io.riverdb.format.catalog.CatalogDefinitionRecord;
import io.riverdb.format.catalog.CatalogDefinitionRecordCodec;
import io.riverdb.format.catalog.CatalogKeyspace;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

/** Complete-set reader for immutable segmented planner statistics. */
final class CatalogStatisticsReader {
  private final CatalogStatisticsHeader header = new CatalogStatisticsHeader();
  private final CatalogAssemblyValidator assembly = new CatalogAssemblyValidator();
  private final CatalogDefinitionRecord child = new CatalogDefinitionRecord();
  private final HeapRowResult row = new HeapRowResult();
  private final ByteBuffer record = ByteBuffer.allocateDirect(
      CatalogDefinitionRecordCodec.MAX_RECORD_BYTES);
  private final CRC32C recordChecksum = new CRC32C();
  private final CRC32C childSetChecksum = new CRC32C();

  StatusCode read(
      IndexedTransactionSession session, TableDefinition table, TableStatistics result) {
    result.reset();
    StatusCode status = header.read(session, table.tableId());
    CatalogDefinitionManifest manifest = header.manifest();
    if (status.isOk() && !CatalogStatisticsIdentity.matches(manifest, table)) {
      status = StatusCode.CONFLICT;
    }
    if (status.isOk()) status = assembly.begin(manifest, childSetChecksum);
    for (int ordinal = 0; status.isOk() && ordinal < manifest.childCount(); ordinal++) {
      status = readChild(session, manifest.firstChildRecordId() + ordinal);
      if (status.isOk()) status = assembly.accept(child);
      if (status.isOk()) status = CatalogStatisticsCodec.decode(
          record, CatalogDefinitionRecordCodec.HEADER_BYTES, child.payloadBytes(),
          table, child.logicalStart(), child.logicalCount(), result);
    }
    if (status.isOk() && (!assembly.complete() || !result.canonicalFor(table))) {
      status = StatusCode.CORRUPTION;
    }
    if (!status.isOk()) result.reset();
    assembly.reset();
    return status;
  }

  private StatusCode readChild(IndexedTransactionSession session, long recordId) {
    StatusCode status = session.fetchByKey(
        CatalogKeyspace.DEFINITION_SPACE, recordId, row);
    if (status == StatusCode.CONFLICT) return StatusCode.CORRUPTION;
    int bytes = status.isOk() ? row.length() : 0;
    if (!status.isOk()) return status;
    if (bytes < CatalogDefinitionRecordCodec.HEADER_BYTES
        || bytes > CatalogDefinitionRecordCodec.MAX_RECORD_BYTES) {
      return StatusCode.CORRUPTION;
    }
    record.clear();
    status = row.copyTo(record);
    record.flip();
    return status.isOk()
        ? CatalogDefinitionRecordCodec.decode(
            record, 0, bytes, child, recordChecksum) : status;
  }
}
