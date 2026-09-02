package io.riverdb.format.catalog;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.FormatBytes;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

final class CatalogDefinitionManifestWriter {
  private CatalogDefinitionManifestWriter() {
  }

  static StatusCode encode(
      ByteBuffer target, int start, int kind, long recordId, long objectId,
      long schemaId, long layoutId, long generation, long firstChild, int children,
      int columns, int keyParts, int logical, int bytes, int childChecksum,
      CRC32C checksum) {
    if (!CatalogDefinitionManifestFormat.writable(target, start, checksum)
        || !CatalogDefinitionManifestFormat.valid(kind, recordId, objectId, schemaId,
            layoutId, generation, firstChild, children, columns, keyParts, logical, bytes)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    FormatBytes.putLong(target, start, CatalogDefinitionManifestCodec.MAGIC);
    FormatBytes.putInt(target, start + 8, CatalogDefinitionManifestCodec.VERSION);
    FormatBytes.putInt(target, start + 12, CatalogDefinitionManifestCodec.BYTES);
    FormatBytes.putInt(target, start + 16, kind);
    FormatBytes.putInt(target, start + 20, 0);
    FormatBytes.putLong(target, start + 24, recordId);
    FormatBytes.putLong(target, start + 32, objectId);
    FormatBytes.putLong(target, start + 40, schemaId);
    FormatBytes.putLong(target, start + 48, layoutId);
    FormatBytes.putLong(target, start + 56, generation);
    FormatBytes.putLong(target, start + 64, firstChild);
    FormatBytes.putInt(target, start + 72, children);
    FormatBytes.putInt(target, start + 76, columns);
    FormatBytes.putInt(target, start + 80, keyParts);
    FormatBytes.putInt(target, start + 84, logical);
    FormatBytes.putInt(target, start + 88, bytes);
    FormatBytes.putInt(target, start + 92, childChecksum);
    FormatBytes.putInt(target, start + 96, ~childChecksum);
    FormatBytes.putInt(target, start + 100, 0);
    int value = FormatBytes.checksum(
        target, start, CatalogDefinitionManifestCodec.CHECKSUM_OFFSET, checksum);
    FormatBytes.putInt(target, start + CatalogDefinitionManifestCodec.CHECKSUM_OFFSET, value);
    FormatBytes.putInt(target, start + CatalogDefinitionManifestCodec.COMPLEMENT_OFFSET, ~value);
    return StatusCode.OK;
  }
}
