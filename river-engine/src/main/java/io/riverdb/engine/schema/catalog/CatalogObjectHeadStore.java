package io.riverdb.engine.schema.catalog;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.format.catalog.CatalogKeyspace;
import io.riverdb.format.catalog.CatalogObjectHeadCodec;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

/** Fixed-buffer insertion and state replacement for one durable catalog object head. */
final class CatalogObjectHeadStore {
  private final ByteBuffer bytes = ByteBuffer.allocateDirect(CatalogObjectHeadCodec.BYTES);
  private final CRC32C checksum = new CRC32C();

  StatusCode insertReady(IndexedTransactionSession session, CatalogReservation reservation) {
    StatusCode status = encode(CatalogObjectHeadCodec.STATE_READY,
        reservation.objectId(), reservation.schemaId(), reservation.catalogGeneration(),
        reservation.manifestRecordId());
    return status.isOk() ? session.insert(
        CatalogKeyspace.OBJECT_HEAD_SPACE, reservation.objectId(), bytes) : status;
  }

  StatusCode updateReady(IndexedTransactionSession session, CatalogReservation reservation) {
    StatusCode status = encode(CatalogObjectHeadCodec.STATE_READY,
        reservation.objectId(), reservation.schemaId(), reservation.catalogGeneration(),
        reservation.manifestRecordId());
    return status.isOk() ? session.update(
        CatalogKeyspace.OBJECT_HEAD_SPACE, reservation.objectId(), bytes) : status;
  }

  StatusCode updateTombstone(
      IndexedTransactionSession session, long objectId, long catalogGeneration) {
    StatusCode status = encode(
        CatalogObjectHeadCodec.STATE_TOMBSTONE,
        objectId,
        0,
        catalogGeneration,
        0);
    return status.isOk() ? session.update(
        CatalogKeyspace.OBJECT_HEAD_SPACE, objectId, bytes) : status;
  }

  private StatusCode encode(
      int state, long objectId, long schemaId, long generation, long manifestId) {
    bytes.clear();
    StatusCode status = CatalogObjectHeadCodec.encode(bytes, 0, state,
        objectId, schemaId, generation, manifestId, checksum);
    bytes.position(0);
    bytes.limit(CatalogObjectHeadCodec.BYTES);
    return status;
  }
}
