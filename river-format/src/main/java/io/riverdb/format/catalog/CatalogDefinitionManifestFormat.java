package io.riverdb.format.catalog;

import io.riverdb.base.sql.SqlShapeLimits;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

final class CatalogDefinitionManifestFormat {
  private CatalogDefinitionManifestFormat() {
  }

  static boolean writable(ByteBuffer target, int start, CRC32C checksum) {
    return target != null && !target.isReadOnly() && checksum != null && start >= 0
        && start <= target.limit() - CatalogDefinitionManifestCodec.BYTES;
  }

  static boolean valid(
      int kind, long recordId, long objectId, long schemaId, long layoutId,
      long generation, long firstChild, int children, int columns, int keyParts,
      int logical, int bytes) {
    if (!validIdentity(recordId, objectId, schemaId, layoutId, generation)
        || !validShape(firstChild, children, columns, logical, bytes)
        || recordId >= firstChild && recordId - firstChild < children) return false;
    if (kind == CatalogDefinitionManifestCodec.KIND_TABLE) {
      return children <= SqlShapeLimits.MAX_SCHEMA_CHUNKS && keyParts >= 0
          && keyParts <= SqlShapeLimits.MAX_TABLE_KEY_PARTS;
    }
    return kind == CatalogDefinitionManifestCodec.KIND_STATISTICS
        && children == maximumStatisticsChunks(columns)
        && keyParts == 0 && logical == columns;
  }

  static boolean validRange(long first, int count) {
    return first > 0 && count > 0 && first <= Long.MAX_VALUE - count + 1;
  }

  static int maximumStatisticsChunks(int columns) {
    return (columns + CatalogDefinitionRecordCodec.MAX_STATISTICS_COLUMNS - 1)
        / CatalogDefinitionRecordCodec.MAX_STATISTICS_COLUMNS;
  }

  private static boolean validIdentity(
      long recordId, long objectId, long schemaId, long layoutId, long generation) {
    return recordId > 0 && CatalogKeyspace.validObjectHead(objectId)
        && schemaId > 0 && layoutId > 0 && generation > 0;
  }

  private static boolean validShape(
      long firstChild, int children, int columns, int logical, int bytes) {
    return validRange(firstChild, children) && columns > 0
        && columns <= SqlShapeLimits.MAX_TABLE_COLUMNS && logical >= columns
        && bytes >= children && bytes <= SqlShapeLimits.MAX_ENCODED_SCHEMA_BYTES;
  }
}
