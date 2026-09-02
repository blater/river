package io.riverdb.format.catalog;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.FormatBytes;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

final class CatalogDefinitionManifestReader {
  private CatalogDefinitionManifestReader() {
  }

  static StatusCode decode(
      ByteBuffer source, int start, CatalogDefinitionManifest result, CRC32C checksum) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (source == null || checksum == null || start < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (start > source.limit() - CatalogDefinitionManifestCodec.BYTES) {
      return StatusCode.CORRUPTION;
    }
    int stored = FormatBytes.getInt(
        source, start + CatalogDefinitionManifestCodec.CHECKSUM_OFFSET);
    int childChecksum = FormatBytes.getInt(source, start + 92);
    if (!validEnvelope(source, start, stored, childChecksum, checksum)) {
      return StatusCode.CORRUPTION;
    }
    return decodeFields(source, start, childChecksum, result);
  }

  private static StatusCode decodeFields(
      ByteBuffer source, int start, int childChecksum, CatalogDefinitionManifest result) {
    int kind = FormatBytes.getInt(source, start + 16);
    long recordId = FormatBytes.getLong(source, start + 24);
    long objectId = FormatBytes.getLong(source, start + 32);
    long schemaId = FormatBytes.getLong(source, start + 40);
    long layoutId = FormatBytes.getLong(source, start + 48);
    long generation = FormatBytes.getLong(source, start + 56);
    long firstChild = FormatBytes.getLong(source, start + 64);
    int children = FormatBytes.getInt(source, start + 72);
    int columns = FormatBytes.getInt(source, start + 76);
    int keyParts = FormatBytes.getInt(source, start + 80);
    int logical = FormatBytes.getInt(source, start + 84);
    int bytes = FormatBytes.getInt(source, start + 88);
    if (!CatalogDefinitionManifestFormat.valid(kind, recordId, objectId, schemaId,
        layoutId, generation, firstChild, children, columns, keyParts, logical, bytes)) {
      return StatusCode.CORRUPTION;
    }
    result.set(kind, recordId, objectId, schemaId, layoutId, generation,
        firstChild, children, columns, keyParts, logical, bytes, childChecksum);
    return StatusCode.OK;
  }

  private static boolean validEnvelope(
      ByteBuffer source, int start, int stored, int childChecksum, CRC32C checksum) {
    return FormatBytes.getLong(source, start) == CatalogDefinitionManifestCodec.MAGIC
        && FormatBytes.getInt(source, start + 8) == CatalogDefinitionManifestCodec.VERSION
        && FormatBytes.getInt(source, start + 12) == CatalogDefinitionManifestCodec.BYTES
        && FormatBytes.getInt(source, start + 20) == 0
        && FormatBytes.getInt(source, start + 96) == ~childChecksum
        && FormatBytes.getInt(source, start + 100) == 0
        && FormatBytes.getInt(
            source, start + CatalogDefinitionManifestCodec.COMPLEMENT_OFFSET) == ~stored
        && FormatBytes.checksum(
            source, start, CatalogDefinitionManifestCodec.CHECKSUM_OFFSET, checksum) == stored;
  }
}
