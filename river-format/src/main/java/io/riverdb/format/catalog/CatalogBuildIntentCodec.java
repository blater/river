package io.riverdb.format.catalog;

import io.riverdb.base.error.StatusCode;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

/** Fixed durable progress record for a bounded private catalog-definition build. */
public final class CatalogBuildIntentCodec {
  public static final int VERSION = 4;
  public static final int BYTES = 160;
  public static final int STATE_BUILDING = 1;
  public static final int STATE_CLEANUP = 2;
  public static final int STATE_READY = 3;
  public static final int KIND_INITIAL = 1;
  public static final int KIND_SUCCESSOR = 2;

  private CatalogBuildIntentCodec() {
  }

  public static StatusCode encode(
      ByteBuffer target, int start, int state, long objectId, long schemaId,
      long rowLayoutId, long generation, long manifestRecordId,
      long firstChildRecordId, int childCount, int nextChild, int cleanupCursor,
      int payloadBytes, long catalogBytes, CRC32C checksum) {
    return encode(target, start, state, KIND_INITIAL,
        objectId, schemaId, rowLayoutId, generation, manifestRecordId,
        firstChildRecordId, childCount, nextChild, cleanupCursor,
        payloadBytes, catalogBytes, 0, 0, 0,
        0, 0, 0, 0, 0, 0, checksum);
  }

  public static StatusCode encodeWithKeys(
      ByteBuffer target, int start, int state, long objectId, long schemaId,
      long rowLayoutId, long generation, long manifestRecordId,
      long firstChildRecordId, int childCount, int nextChild, int cleanupCursor,
      int payloadBytes, long catalogBytes, long firstKeyId, int keyCount,
      int physicalIndexCount, int nextPhysicalIndex, int indexCleanupCursor,
      CRC32C checksum) {
    return encode(target, start, state, KIND_INITIAL,
        objectId, schemaId, rowLayoutId, generation, manifestRecordId,
        firstChildRecordId, childCount, nextChild, cleanupCursor,
        payloadBytes, catalogBytes, 0, 0, 0, firstKeyId, keyCount,
        physicalIndexCount, nextPhysicalIndex, indexCleanupCursor, 0, checksum);
  }

  public static StatusCode encodeSuccessor(
      ByteBuffer target, int start, int state, long objectId, long schemaId,
      long rowLayoutId, long generation, long manifestRecordId,
      long firstChildRecordId, int childCount, int nextChild, int cleanupCursor,
      int payloadBytes, long catalogBytes, long predecessorSchemaId,
      long predecessorGeneration, long predecessorManifestRecordId, CRC32C checksum) {
    return encode(target, start, state, KIND_SUCCESSOR,
        objectId, schemaId, rowLayoutId, generation, manifestRecordId,
        firstChildRecordId, childCount, nextChild, cleanupCursor,
        payloadBytes, catalogBytes, predecessorSchemaId,
        predecessorGeneration, predecessorManifestRecordId,
        0, 0, 0, 0, 0, 0, checksum);
  }

  public static StatusCode encodeSuccessorWithKeys(
      ByteBuffer target, int start, int state, long objectId, long schemaId,
      long rowLayoutId, long generation, long manifestRecordId,
      long firstChildRecordId, int childCount, int nextChild, int cleanupCursor,
      int payloadBytes, long catalogBytes, long predecessorSchemaId,
      long predecessorGeneration, long predecessorManifestRecordId,
      long firstKeyId, int keyCount, int physicalIndexCount,
      int nextPhysicalIndex, int indexCleanupCursor, CRC32C checksum) {
    return encode(target, start, state, KIND_SUCCESSOR,
        objectId, schemaId, rowLayoutId, generation, manifestRecordId,
        firstChildRecordId, childCount, nextChild, cleanupCursor,
        payloadBytes, catalogBytes, predecessorSchemaId,
        predecessorGeneration, predecessorManifestRecordId,
        firstKeyId, keyCount, physicalIndexCount,
        nextPhysicalIndex, indexCleanupCursor, 0, checksum);
  }

  public static StatusCode encodeWithCleanupHorizon(
      ByteBuffer target, int start, int state, int kind,
      long objectId, long schemaId, long rowLayoutId, long generation,
      long manifestRecordId, long firstChildRecordId, int childCount,
      int nextChild, int cleanupCursor, int payloadBytes, long catalogBytes,
      long predecessorSchemaId, long predecessorGeneration,
      long predecessorManifestRecordId, long firstKeyId, int keyCount,
      int physicalIndexCount, int nextPhysicalIndex, int indexCleanupCursor,
      int indexCleanupHorizon, CRC32C checksum) {
    return encode(target, start, state, kind, objectId, schemaId, rowLayoutId, generation,
        manifestRecordId, firstChildRecordId, childCount, nextChild, cleanupCursor,
        payloadBytes, catalogBytes, predecessorSchemaId, predecessorGeneration,
        predecessorManifestRecordId, firstKeyId, keyCount, physicalIndexCount,
        nextPhysicalIndex, indexCleanupCursor, indexCleanupHorizon, checksum);
  }

  private static StatusCode encode(
      ByteBuffer target, int start, int state, int kind,
      long objectId, long schemaId, long rowLayoutId, long generation,
      long manifestRecordId, long firstChildRecordId, int childCount,
      int nextChild, int cleanupCursor, int payloadBytes, long catalogBytes,
      long predecessorSchemaId, long predecessorGeneration,
      long predecessorManifestRecordId, long firstKeyId, int keyCount,
      int physicalIndexCount, int nextPhysicalIndex, int indexCleanupCursor,
      int indexCleanupHorizon, CRC32C checksum) {
    return CatalogBuildIntentEncoding.encode(
        target, start, state, kind, objectId, schemaId, rowLayoutId, generation,
        manifestRecordId, firstChildRecordId, childCount, nextChild, cleanupCursor,
        payloadBytes, catalogBytes, predecessorSchemaId, predecessorGeneration,
        predecessorManifestRecordId, firstKeyId, keyCount, physicalIndexCount,
        nextPhysicalIndex, indexCleanupCursor, indexCleanupHorizon, checksum);
  }

  public static StatusCode decode(
      ByteBuffer source, int start, CatalogBuildIntent result, CRC32C checksum) {
    return CatalogBuildIntentDecoding.decode(source, start, result, checksum);
  }
}
