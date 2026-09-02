package io.riverdb.engine.schema.catalog;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.format.catalog.CatalogDefinitionManifestCodec;
import io.riverdb.format.catalog.CatalogDefinitionRecord;
import io.riverdb.format.catalog.CatalogDefinitionRecordCodec;
import io.riverdb.format.catalog.CatalogKeyspace;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

/** Canonical private child/manifest writer and atomic object-head publisher. */
final class CatalogDefinitionWriter {
  private final CatalogTablePayloadPacker packer = new CatalogTablePayloadPacker();
  private final CatalogDefinitionRecord child = new CatalogDefinitionRecord();
  private final HeapRowResult row = new HeapRowResult();
  private final ByteBuffer payload = ByteBuffer.allocateDirect(
      CatalogDefinitionRecordCodec.MAX_PAYLOAD_BYTES);
  private final ByteBuffer record = ByteBuffer.allocateDirect(
      CatalogDefinitionRecordCodec.MAX_RECORD_BYTES);
  private final CRC32C recordChecksum = new CRC32C();
  private final CRC32C childSetChecksum = new CRC32C();

  StatusCode plan(TableDescriptor table, CatalogTablePayloadPlan result) {
    return packer.plan(table, result);
  }

  StatusCode writeChildren(
      IndexedTransactionSession session, TableDescriptor table,
      CatalogReservation reservation, CatalogTablePayloadPlan plan,
      int first, int count) {
    if (session == null || table == null || reservation == null || plan == null
        || first < 0 || count <= 0 || first > plan.chunkCount() - count) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int index = first; index < first + count; index++) {
      StatusCode status = writeChild(session, table, reservation, plan, index);
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  StatusCode writeManifest(
      IndexedTransactionSession session, TableDescriptor table,
      CatalogReservation reservation, CatalogTablePayloadPlan plan) {
    if (session == null || table == null || reservation == null || plan == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    childSetChecksum.reset();
    for (int index = 0; index < reservation.childCount(); index++) {
      StatusCode status = readChild(session, reservation.firstChildRecordId() + index);
      if (!status.isOk()) return referenced(status);
      if (!matches(reservation, index)) return StatusCode.CORRUPTION;
      CatalogDefinitionRecordCodec.updateChildSetChecksum(
          childSetChecksum, child.recordChecksum());
    }
    return encodeManifest(session, table, reservation, plan, keyParts(plan));
  }

  private StatusCode writeChild(
      IndexedTransactionSession session, TableDescriptor table,
      CatalogReservation reservation, CatalogTablePayloadPlan plan, int index) {
    int payloadBytes = plan.payloadBytesAt(index);
    payload.clear();
    payload.limit(payloadBytes);
    StatusCode status = packer.encodeChunk(table, plan, index, payload, 0);
    if (!status.isOk()) return status;
    payload.position(0);
    record.clear();
    long recordId = reservation.firstChildRecordId() + index;
    status = CatalogDefinitionRecordCodec.encode(record, 0, recordId,
        reservation.objectId(), reservation.schemaId(), reservation.catalogGeneration(),
        plan.kindAt(index), index,
        plan.logicalStartAt(index), plan.logicalCountAt(index), payload, recordChecksum);
    int recordBytes = CatalogDefinitionRecordCodec.HEADER_BYTES + payloadBytes;
    record.position(0);
    record.limit(recordBytes);
    return status.isOk()
        ? session.insert(CatalogKeyspace.DEFINITION_SPACE, recordId, record) : status;
  }

  private StatusCode encodeManifest(
      IndexedTransactionSession session, TableDescriptor table,
      CatalogReservation reservation, CatalogTablePayloadPlan plan, int keyParts) {
    record.clear();
    StatusCode status = CatalogDefinitionManifestCodec.encode(record, 0,
        CatalogDefinitionManifestCodec.KIND_TABLE, reservation.manifestRecordId(),
        reservation.objectId(), reservation.schemaId(), reservation.rowLayoutId(),
        reservation.catalogGeneration(),
        reservation.firstChildRecordId(), reservation.childCount(), table.columnCount(),
        keyParts, table.columnCount() + keyParts, plan.totalPayloadBytes(),
        (int) childSetChecksum.getValue(), recordChecksum);
    record.position(0);
    record.limit(CatalogDefinitionManifestCodec.BYTES);
    return status.isOk() ? session.insert(CatalogKeyspace.DEFINITION_SPACE,
        reservation.manifestRecordId(), record) : status;
  }

  private StatusCode readChild(IndexedTransactionSession session, long recordId) {
    StatusCode status = session.fetchByKey(CatalogKeyspace.DEFINITION_SPACE, recordId, row);
    int bytes = status.isOk() ? row.length() : 0;
    if (!status.isOk()) return status;
    if (bytes < CatalogDefinitionRecordCodec.HEADER_BYTES
        || bytes > CatalogDefinitionRecordCodec.MAX_RECORD_BYTES) return StatusCode.CORRUPTION;
    record.clear();
    status = row.copyTo(record);
    record.flip();
    return status.isOk()
        ? CatalogDefinitionRecordCodec.decode(record, 0, bytes, child, recordChecksum)
        : status;
  }

  private boolean matches(CatalogReservation reservation, int index) {
    return child.catalogRecordId() == reservation.firstChildRecordId() + index
        && child.objectId() == reservation.objectId()
        && child.schemaId() == reservation.schemaId()
        && child.catalogGeneration() == reservation.catalogGeneration()
        && child.ordinal() == index;
  }

  private static int keyParts(CatalogTablePayloadPlan plan) {
    int parts = 0;
    for (int index = 0; index < plan.chunkCount(); index++) {
      if (plan.kindAt(index) == CatalogDefinitionRecordCodec.KIND_KEY) {
        parts += plan.logicalCountAt(index);
      }
    }
    return parts;
  }

  private static StatusCode referenced(StatusCode status) {
    return status == StatusCode.CONFLICT ? StatusCode.CORRUPTION : status;
  }
}
