package io.riverdb.engine.schema.catalog;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.format.catalog.CatalogBuildIntent;
import io.riverdb.format.catalog.CatalogBuildIntentCodec;
import io.riverdb.format.catalog.CatalogKeyspace;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

/** Durable BUILD/CLEANUP intent reads and progress updates. */
final class CatalogIntentStore {
  private final HeapRowResult row = new HeapRowResult();
  private final ByteBuffer bytes = ByteBuffer.allocateDirect(CatalogBuildIntentCodec.BYTES);
  private final CatalogBuildIntent intent = new CatalogBuildIntent();
  private final CRC32C checksum = new CRC32C();

  StatusCode insert(
      IndexedTransactionSession session, CatalogReservation reservation,
      int payloadBytes, long catalogBytes) {
    StatusCode status = encode(CatalogBuildIntentCodec.STATE_BUILDING,
        reservation, 0, 0, 0, 0, 0, payloadBytes, catalogBytes);
    return status.isOk() ? session.insert(CatalogKeyspace.BUILD_INTENT_SPACE,
        reservation.objectId(), bytes) : status;
  }

  StatusCode updateProgress(
      IndexedTransactionSession session, CatalogReservation reservation,
      int nextChild, int payloadBytes, long catalogBytes) {
    StatusCode status = encode(CatalogBuildIntentCodec.STATE_BUILDING,
        reservation, nextChild, 0, reservation.nextPhysicalIndex(), 0,
        0, payloadBytes, catalogBytes);
    return status.isOk() ? session.update(CatalogKeyspace.BUILD_INTENT_SPACE,
        reservation.objectId(), bytes) : status;
  }

  StatusCode updateCleanup(
      IndexedTransactionSession session, CatalogBuildIntent value, int cleanupCursor) {
    StatusCode status = encode(CatalogBuildIntentCodec.STATE_CLEANUP,
        value, value.nextChild(), cleanupCursor,
        value.nextPhysicalIndex(), value.indexCleanupCursor(), 0);
    return status.isOk() ? session.update(CatalogKeyspace.BUILD_INTENT_SPACE,
        value.objectId(), bytes) : status;
  }

  StatusCode updateReady(
      IndexedTransactionSession session, CatalogReservation reservation,
      int payloadBytes, long catalogBytes) {
    StatusCode status = encode(CatalogBuildIntentCodec.STATE_READY,
        reservation, reservation.childCount(), 0,
        reservation.physicalIndexCount(), 0, 0, payloadBytes, catalogBytes);
    return status.isOk() ? session.update(CatalogKeyspace.BUILD_INTENT_SPACE,
        reservation.objectId(), bytes) : status;
  }

  StatusCode read(IndexedTransactionSession session, long objectId) {
    StatusCode status = session.fetchByKey(
        CatalogKeyspace.BUILD_INTENT_SPACE, objectId, row);
    return status.isOk() ? decode(row) : status;
  }

  StatusCode decode(HeapRowResult source) {
    if (source == null || source.length() != CatalogBuildIntentCodec.BYTES) {
      return StatusCode.CORRUPTION;
    }
    bytes.clear();
    StatusCode status = source.copyTo(bytes);
    bytes.flip();
    return status.isOk()
        ? CatalogBuildIntentCodec.decode(bytes, 0, intent, checksum) : status;
  }

  CatalogBuildIntent value() { return intent; }

  StatusCode delete(IndexedTransactionSession session, long objectId) {
    return session.delete(CatalogKeyspace.BUILD_INTENT_SPACE, objectId);
  }

  StatusCode updateIndexProgress(
      IndexedTransactionSession session, CatalogReservation reservation,
      int nextPhysicalIndex, int payloadBytes, long catalogBytes) {
    StatusCode status = encode(CatalogBuildIntentCodec.STATE_BUILDING,
        reservation, reservation.childCount(), 0, nextPhysicalIndex, 0,
        0, payloadBytes, catalogBytes);
    return status.isOk() ? session.update(CatalogKeyspace.BUILD_INTENT_SPACE,
        reservation.objectId(), bytes) : status;
  }

  StatusCode updateIndexCleanup(
      IndexedTransactionSession session, CatalogBuildIntent value,
      int indexCleanupCursor) {
    StatusCode status = encode(CatalogBuildIntentCodec.STATE_CLEANUP,
        value, value.nextChild(), value.cleanupCursor(),
        value.nextPhysicalIndex(), indexCleanupCursor, 0);
    return status.isOk() ? session.update(CatalogKeyspace.BUILD_INTENT_SPACE,
        value.objectId(), bytes) : status;
  }

  private StatusCode encode(
      int state, CatalogReservation reservation, int next, int cleanup,
      int nextPhysicalIndex, int indexCleanupCursor, int indexCleanupHorizon,
      int payloadBytes, long catalogBytes) {
    bytes.clear();
    StatusCode status = CatalogBuildIntentCodec.encodeWithCleanupHorizon(
        bytes, 0, state, reservation.kind(), reservation.objectId(),
        reservation.schemaId(), reservation.rowLayoutId(), reservation.catalogGeneration(),
        reservation.manifestRecordId(), reservation.firstChildRecordId(),
        reservation.childCount(), next, cleanup, payloadBytes, catalogBytes,
        reservation.predecessorSchemaId(), reservation.predecessorGeneration(),
        reservation.predecessorManifestRecordId(), reservation.firstKeyId(),
        reservation.keyCount(), reservation.physicalIndexCount(), nextPhysicalIndex,
        indexCleanupCursor, indexCleanupHorizon, checksum);
    bytes.position(0);
    bytes.limit(CatalogBuildIntentCodec.BYTES);
    return status;
  }

  private StatusCode encode(
      int state, CatalogBuildIntent value, int next, int cleanup,
      int nextPhysicalIndex, int indexCleanupCursor, int indexCleanupHorizon) {
    bytes.clear();
    StatusCode status = CatalogBuildIntentCodec.encodeWithCleanupHorizon(
        bytes, 0, state, value.kind(), value.objectId(), value.schemaId(),
        value.rowLayoutId(), value.catalogGeneration(), value.manifestRecordId(),
        value.firstChildRecordId(), value.childCount(), next, cleanup,
        value.payloadBytes(), value.catalogBytes(), value.predecessorSchemaId(),
        value.predecessorGeneration(), value.predecessorManifestRecordId(),
        value.firstKeyId(), value.keyCount(), value.physicalIndexCount(),
        nextPhysicalIndex, indexCleanupCursor, indexCleanupHorizon, checksum);
    bytes.position(0);
    bytes.limit(CatalogBuildIntentCodec.BYTES);
    return status;
  }

  StatusCode updateIndexCleanupHorizon(
      IndexedTransactionSession session, CatalogBuildIntent value, int horizon) {
    StatusCode status = encode(CatalogBuildIntentCodec.STATE_CLEANUP,
        value, value.nextChild(), value.cleanupCursor(), value.nextPhysicalIndex(),
        value.indexCleanupCursor(), horizon);
    return status.isOk() ? session.update(CatalogKeyspace.BUILD_INTENT_SPACE,
        value.objectId(), bytes) : status;
  }
}
