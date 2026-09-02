package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.schema.catalog.CatalogRecordRange;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.format.catalog.CatalogChunkPlan;
import io.riverdb.format.catalog.CatalogChunkPlanner;
import io.riverdb.format.catalog.CatalogDefinitionManifest;
import io.riverdb.format.catalog.CatalogDefinitionManifestCodec;
import io.riverdb.format.catalog.CatalogDefinitionRecord;
import io.riverdb.format.catalog.CatalogDefinitionRecordCodec;
import io.riverdb.format.catalog.CatalogKeyspace;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

/** Stages one complete immutable statistics generation in the caller transaction. */
final class CatalogStatisticsWriter {
  private final CatalogStatisticsHeader oldHeader = new CatalogStatisticsHeader();
  private final CatalogRecordRange range = new CatalogRecordRange();
  private final CatalogChunkPlan plan = new CatalogChunkPlan();
  private final CatalogDefinitionRecord decoded = new CatalogDefinitionRecord();
  private final ByteBuffer payload = ByteBuffer.allocateDirect(
      CatalogDefinitionRecordCodec.MAX_PAYLOAD_BYTES);
  private final ByteBuffer record = ByteBuffer.allocateDirect(
      CatalogDefinitionRecordCodec.MAX_RECORD_BYTES);
  private final CRC32C recordChecksum = new CRC32C();
  private final CRC32C childSetChecksum = new CRC32C();

  StatusCode write(
      RelationalSession owner, TableDefinition table, TableStatistics statistics) {
    int chunks = chunks(table.columnCount());
    int payloadBytes = table.columnCount() * CatalogStatisticsCodec.COLUMN_BYTES
        + chunks * CatalogStatisticsCodec.HEADER_BYTES;
    StatusCode status = CatalogChunkPlanner.planStatistics(
        table.columnCount(), chunks, payloadBytes, plan);
    boolean replacing = false;
    if (status.isOk()) {
      status = oldHeader.read(owner.indexedSession(), table.tableId());
      replacing = status.isOk();
      if (status == StatusCode.CONFLICT) status = StatusCode.OK;
    }
    if (status.isOk()) status = owner.reserveCatalogRecords(chunks + 1, range);
    childSetChecksum.reset();
    int written = 0;
    boolean published = false;
    while (status.isOk() && written < chunks) {
      status = writeChild(owner.indexedSession(), table, statistics, written);
      if (status.isOk()) written++;
    }
    if (status.isOk()) {
      status = writeHeader(owner.indexedSession(), table, replacing);
      published = status.isOk();
    }
    if (status.isOk() && replacing) {
      status = deleteChildren(owner.indexedSession(), oldHeader.manifest());
    }
    return status.isOk() || published
        ? status : discard(owner.indexedSession(), written, status);
  }

  private StatusCode writeChild(
      IndexedTransactionSession session, TableDefinition table,
      TableStatistics statistics, int ordinal) {
    int first = ordinal * CatalogDefinitionRecordCodec.MAX_STATISTICS_COLUMNS;
    int count = Math.min(
        CatalogDefinitionRecordCodec.MAX_STATISTICS_COLUMNS,
        table.columnCount() - first);
    StatusCode status = CatalogStatisticsCodec.encode(payload, statistics, first, count);
    if (!status.isOk()) return status;
    record.clear();
    long recordId = range.firstRecordId() + ordinal + 1;
    status = CatalogDefinitionRecordCodec.encode(
        record, 0, recordId, table.tableId(), table.statisticsSchemaId(),
        table.statisticsCatalogGeneration(),
        CatalogDefinitionRecordCodec.KIND_STATISTICS,
        ordinal, first, count, payload, recordChecksum);
    int bytes = CatalogDefinitionRecordCodec.HEADER_BYTES + payload.limit();
    record.position(0).limit(bytes);
    if (status.isOk()) status = CatalogDefinitionRecordCodec.decode(
        record, 0, bytes, decoded, recordChecksum);
    if (status.isOk()) CatalogDefinitionRecordCodec.updateChildSetChecksum(
        childSetChecksum, decoded.recordChecksum());
    record.position(0).limit(bytes);
    return status.isOk()
        ? session.insert(CatalogKeyspace.DEFINITION_SPACE, recordId, record) : status;
  }

  private StatusCode writeHeader(
      IndexedTransactionSession session, TableDefinition table, boolean replacing) {
    record.clear();
    StatusCode status = CatalogDefinitionManifestCodec.encode(
        record, 0, CatalogDefinitionManifestCodec.KIND_STATISTICS,
        range.firstRecordId(), table.tableId(), table.statisticsSchemaId(),
        table.statisticsRowLayoutId(), table.statisticsCatalogGeneration(),
        range.firstRecordId() + 1, plan.totalChunks(), table.columnCount(), 0,
        table.columnCount(), plan.payloadBytes(),
        (int) childSetChecksum.getValue(), recordChecksum);
    record.position(0).limit(CatalogDefinitionManifestCodec.BYTES);
    if (!status.isOk()) return status;
    long key = RelationalKey.tableStatisticsKey(table.tableId());
    return replacing
        ? session.update(RelationalKey.CATALOG_SEQUENCE_SPACE, key, record)
        : session.insert(RelationalKey.CATALOG_SEQUENCE_SPACE, key, record);
  }

  private StatusCode discard(
      IndexedTransactionSession session, int written, StatusCode failure) {
    for (int ordinal = 0; ordinal < written; ordinal++) {
      StatusCode deleted = session.delete(
          CatalogKeyspace.DEFINITION_SPACE, range.firstRecordId() + ordinal + 1);
      if (!deleted.isOk() && deleted != StatusCode.CONFLICT) return deleted;
    }
    return failure;
  }

  static StatusCode deleteChildren(
      IndexedTransactionSession session, CatalogDefinitionManifest manifest) {
    for (int index = 0; index < manifest.childCount(); index++) {
      StatusCode status = session.delete(
          CatalogKeyspace.DEFINITION_SPACE, manifest.firstChildRecordId() + index);
      if (!status.isOk() && status != StatusCode.CONFLICT) return status;
    }
    return StatusCode.OK;
  }

  private static int chunks(int columns) {
    return (columns + CatalogDefinitionRecordCodec.MAX_STATISTICS_COLUMNS - 1)
        / CatalogDefinitionRecordCodec.MAX_STATISTICS_COLUMNS;
  }
}
