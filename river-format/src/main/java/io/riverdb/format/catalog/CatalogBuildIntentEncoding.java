package io.riverdb.format.catalog;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.FormatBytes;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

final class CatalogBuildIntentEncoding {
  private CatalogBuildIntentEncoding() { }

  static StatusCode encode(
      ByteBuffer target, int start, int state, int kind,
      long objectId, long schemaId, long rowLayoutId, long generation,
      long manifestRecordId, long firstChildRecordId, int childCount,
      int nextChild, int cleanupCursor, int payloadBytes, long catalogBytes,
      long predecessorSchemaId, long predecessorGeneration,
      long predecessorManifestRecordId, long firstKeyId, int keyCount,
      int physicalIndexCount, int nextPhysicalIndex, int indexCleanupCursor,
      int indexCleanupHorizon, CRC32C checksum) {
    if (!writable(target, start, checksum)
        || !CatalogBuildIntentValidation.valid(
            state, kind, objectId, schemaId, rowLayoutId, generation,
            manifestRecordId, firstChildRecordId, childCount, nextChild,
            cleanupCursor, payloadBytes, catalogBytes, predecessorSchemaId,
            predecessorGeneration, predecessorManifestRecordId,
            firstKeyId, keyCount, physicalIndexCount,
            nextPhysicalIndex, indexCleanupCursor, indexCleanupHorizon)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    FormatBytes.putLong(target, start, CatalogBuildIntentLayout.MAGIC);
    FormatBytes.putInt(target, start + 8, CatalogBuildIntentCodec.VERSION);
    FormatBytes.putInt(target, start + 12, CatalogBuildIntentCodec.BYTES);
    FormatBytes.putInt(target, start + 16, state);
    FormatBytes.putInt(target, start + 20, kind);
    FormatBytes.putLong(target, start + 24, objectId);
    FormatBytes.putLong(target, start + 32, schemaId);
    FormatBytes.putLong(target, start + 40, rowLayoutId);
    FormatBytes.putLong(target, start + 48, generation);
    FormatBytes.putLong(target, start + 56, manifestRecordId);
    FormatBytes.putLong(target, start + 64, firstChildRecordId);
    FormatBytes.putInt(target, start + 72, childCount);
    FormatBytes.putInt(target, start + 76, nextChild);
    FormatBytes.putInt(target, start + 80, cleanupCursor);
    FormatBytes.putInt(target, start + 84, payloadBytes);
    FormatBytes.putLong(target, start + 88, catalogBytes);
    FormatBytes.putLong(target, start + 96, predecessorSchemaId);
    FormatBytes.putLong(target, start + 104, predecessorGeneration);
    FormatBytes.putLong(target, start + 112, predecessorManifestRecordId);
    FormatBytes.putLong(target, start + 120, firstKeyId);
    FormatBytes.putInt(target, start + 128, keyCount);
    FormatBytes.putInt(target, start + 132, physicalIndexCount);
    FormatBytes.putInt(target, start + 136, nextPhysicalIndex);
    FormatBytes.putInt(target, start + 140, indexCleanupCursor);
    FormatBytes.putInt(target, start + 144, indexCleanupHorizon);
    FormatBytes.putInt(target, start + 148, 0);
    int value = FormatBytes.checksum(
        target, start, CatalogBuildIntentLayout.CHECKSUM_OFFSET, checksum);
    FormatBytes.putInt(target, start + CatalogBuildIntentLayout.CHECKSUM_OFFSET, value);
    FormatBytes.putInt(target, start + CatalogBuildIntentLayout.COMPLEMENT_OFFSET, ~value);
    return StatusCode.OK;
  }

  private static boolean writable(ByteBuffer target, int start, CRC32C checksum) {
    return target != null && !target.isReadOnly() && checksum != null && start >= 0
        && start <= target.limit() - CatalogBuildIntentCodec.BYTES;
  }
}
