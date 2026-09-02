package io.riverdb.format.catalog;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.FormatBytes;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

final class CatalogBuildIntentDecoding {
  private CatalogBuildIntentDecoding() { }

  static StatusCode decode(
      ByteBuffer source, int start, CatalogBuildIntent result, CRC32C checksum) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (source == null || checksum == null || start < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (start > source.limit() - CatalogBuildIntentCodec.BYTES) {
      return StatusCode.CORRUPTION;
    }
    int state = FormatBytes.getInt(source, start + 16);
    int kind = FormatBytes.getInt(source, start + 20);
    long objectId = FormatBytes.getLong(source, start + 24);
    long schemaId = FormatBytes.getLong(source, start + 32);
    long layoutId = FormatBytes.getLong(source, start + 40);
    long generation = FormatBytes.getLong(source, start + 48);
    long manifestId = FormatBytes.getLong(source, start + 56);
    long firstChild = FormatBytes.getLong(source, start + 64);
    int childCount = FormatBytes.getInt(source, start + 72);
    int nextChild = FormatBytes.getInt(source, start + 76);
    int cleanupCursor = FormatBytes.getInt(source, start + 80);
    int payloadBytes = FormatBytes.getInt(source, start + 84);
    long catalogBytes = FormatBytes.getLong(source, start + 88);
    long predecessorSchema = FormatBytes.getLong(source, start + 96);
    long predecessorGeneration = FormatBytes.getLong(source, start + 104);
    long predecessorManifest = FormatBytes.getLong(source, start + 112);
    long firstKeyId = FormatBytes.getLong(source, start + 120);
    int keyCount = FormatBytes.getInt(source, start + 128);
    int physicalIndexCount = FormatBytes.getInt(source, start + 132);
    int nextPhysicalIndex = FormatBytes.getInt(source, start + 136);
    int indexCleanupCursor = FormatBytes.getInt(source, start + 140);
    int indexCleanupHorizon = FormatBytes.getInt(source, start + 144);
    int stored = FormatBytes.getInt(source, start + CatalogBuildIntentLayout.CHECKSUM_OFFSET);
    if (!header(source, start, stored, checksum)
        || !CatalogBuildIntentValidation.valid(
            state, kind, objectId, schemaId, layoutId, generation, manifestId,
            firstChild, childCount, nextChild, cleanupCursor, payloadBytes, catalogBytes,
            predecessorSchema, predecessorGeneration, predecessorManifest,
            firstKeyId, keyCount, physicalIndexCount,
            nextPhysicalIndex, indexCleanupCursor,
            indexCleanupHorizon)) return StatusCode.CORRUPTION;
    result.set(state, kind, objectId, schemaId, layoutId, generation, manifestId,
        firstChild, childCount, nextChild, cleanupCursor, payloadBytes, catalogBytes,
        predecessorSchema, predecessorGeneration, predecessorManifest,
        firstKeyId, keyCount, physicalIndexCount,
        nextPhysicalIndex, indexCleanupCursor, indexCleanupHorizon);
    return StatusCode.OK;
  }

  private static boolean header(
      ByteBuffer source, int start, int stored, CRC32C checksum) {
    return FormatBytes.getLong(source, start) == CatalogBuildIntentLayout.MAGIC
        && FormatBytes.getInt(source, start + 8) == CatalogBuildIntentCodec.VERSION
        && FormatBytes.getInt(source, start + 12) == CatalogBuildIntentCodec.BYTES
        && FormatBytes.getInt(source, start + 148) == 0
        && FormatBytes.getInt(source, start + CatalogBuildIntentLayout.COMPLEMENT_OFFSET)
            == ~stored
        && FormatBytes.checksum(
            source, start, CatalogBuildIntentLayout.CHECKSUM_OFFSET, checksum) == stored;
  }
}
