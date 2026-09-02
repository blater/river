package io.riverdb.engine.schema.catalog;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.format.catalog.CatalogAllocationWatermark;
import io.riverdb.format.catalog.CatalogAllocationWatermarkCodec;
import io.riverdb.format.catalog.CatalogKeyspace;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;
/** Durable catalog-v2 identity allocator used only inside serialized allocation transactions. */
final class CatalogIdAllocator {
  private final CatalogAllocationWatermark watermark = new CatalogAllocationWatermark();
  private final HeapRowResult row = new HeapRowResult();
  private final ByteBuffer bytes = ByteBuffer.allocateDirect(CatalogAllocationWatermarkCodec.BYTES);
  private final CRC32C checksum = new CRC32C();

  StatusCode initialize(IndexedTransactionSession session) {
    StatusCode status = encode(1, 1, 1, 1, 1);
    return status.isOk() ? session.insert(CatalogKeyspace.SYSTEM_SPACE,
        CatalogKeyspace.ALLOCATION_WATERMARK_KEY, bytes) : status;
  }
  StatusCode validate(IndexedTransactionSession session) {
    return read(session);
  }
  StatusCode reserveRecords(
      IndexedTransactionSession session, int recordCount, CatalogRecordRange result) {
    if (session == null || result == null || recordCount <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode status = read(session);
    if (!status.isOk()) return status;
    long first = watermark.nextCatalogRecordId();
    if (first <= 0 || first >= Long.MAX_VALUE
        || recordCount > Long.MAX_VALUE - first) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    status = encode(
        watermark.nextObjectId(), watermark.nextSchemaId(),
        watermark.nextRowLayoutId(), first + recordCount, watermark.nextKeyId());
    if (status.isOk()) status = session.update(
        CatalogKeyspace.SYSTEM_SPACE, CatalogKeyspace.ALLOCATION_WATERMARK_KEY, bytes);
    if (status.isOk()) result.set(first, recordCount);
    return status;
  }
  StatusCode reserve(
      IndexedTransactionSession session, int childCount, int keyCount,
      CatalogReservation result) {
    if (session == null || result == null || childCount <= 0 || keyCount < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode status = read(session);
    if (!status.isOk()) return status;
    long objectId = watermark.nextObjectId();
    long schemaId = watermark.nextSchemaId();
    long rowLayoutId = watermark.nextRowLayoutId();
    long manifestId = watermark.nextCatalogRecordId();
    long firstKeyId = watermark.nextKeyId();
    long recordCount = (long) childCount + 1;
    if (!watermark.canAllocateObjectId() || !watermark.canAllocateSchemaId()
        || !watermark.canAllocateRowLayoutId()
        || !watermark.canAllocateKeyIds(keyCount)
        || manifestId >= Long.MAX_VALUE || recordCount >= Long.MAX_VALUE
        || manifestId > Long.MAX_VALUE - recordCount) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    status = encode(
        objectId + 1, schemaId + 1, rowLayoutId + 1,
        manifestId + recordCount, firstKeyId + keyCount);
    if (status.isOk()) status = session.update(CatalogKeyspace.SYSTEM_SPACE,
        CatalogKeyspace.ALLOCATION_WATERMARK_KEY, bytes);
    if (status.isOk()) {
      result.setInitial(
          objectId, schemaId, rowLayoutId, 1, manifestId, manifestId + 1,
          childCount, firstKeyId, keyCount);
    }
    return status;
  }

  StatusCode reserveSuccessor(
      IndexedTransactionSession session,
      io.riverdb.engine.schema.TableDescriptor current,
      int childCount,
      int newKeyCount,
      long predecessorSchemaId,
      long predecessorManifestRecordId,
      CatalogReservation result) {
    if (session == null || current == null || result == null
        || childCount <= 0 || newKeyCount < 0
        || predecessorSchemaId <= 0 || predecessorManifestRecordId <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode status = read(session);
    if (!status.isOk()) return status;
    long schemaId = watermark.nextSchemaId();
    long manifestId = watermark.nextCatalogRecordId();
    long firstKeyId = watermark.nextKeyId();
    long recordCount = (long) childCount + 1;
    long generation = current.catalogGeneration();
    if (!io.riverdb.format.catalog.CatalogKeyspace.validObjectHead(current.tableId())
        || current.rowLayoutId() <= 0 || generation >= Long.MAX_VALUE
        || !watermark.canAllocateSchemaId()
        || !watermark.canAllocateKeyIds(newKeyCount)
        || manifestId >= Long.MAX_VALUE || recordCount >= Long.MAX_VALUE
        || manifestId > Long.MAX_VALUE - recordCount) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    status = encode(
        watermark.nextObjectId(), schemaId + 1, watermark.nextRowLayoutId(),
        manifestId + recordCount, firstKeyId + newKeyCount);
    if (status.isOk()) status = session.update(CatalogKeyspace.SYSTEM_SPACE,
        CatalogKeyspace.ALLOCATION_WATERMARK_KEY, bytes);
    if (status.isOk()) {
      result.setSuccessor(
          current.tableId(), schemaId, current.rowLayoutId(), generation + 1,
          manifestId, manifestId + 1, childCount, firstKeyId, newKeyCount,
          predecessorSchemaId, generation, predecessorManifestRecordId);
    }
    return status;
  }
  private StatusCode read(IndexedTransactionSession session) {
    if (session == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = session.fetchByKey(CatalogKeyspace.SYSTEM_SPACE,
        CatalogKeyspace.ALLOCATION_WATERMARK_KEY, row);
    if (!status.isOk()) return status;
    if (row.length() != CatalogAllocationWatermarkCodec.BYTES) return StatusCode.CORRUPTION;
    bytes.clear();
    status = row.copyTo(bytes);
    if (!status.isOk()) return status;
    bytes.flip();
    return CatalogAllocationWatermarkCodec.decode(bytes, 0, watermark, checksum);
  }
  private StatusCode encode(
      long objectId, long schemaId, long rowLayoutId, long recordId, long keyId) {
    bytes.clear();
    StatusCode status = CatalogAllocationWatermarkCodec.encode(
        bytes, 0, objectId, schemaId, rowLayoutId, recordId, keyId, checksum);
    bytes.position(0);
    bytes.limit(CatalogAllocationWatermarkCodec.BYTES);
    return status;
  }
}
