package io.riverdb.format.catalog;

import io.riverdb.base.sql.SqlShapeLimits;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

final class CatalogDefinitionRecordFormat {
  private CatalogDefinitionRecordFormat() {
  }

  static boolean writable(ByteBuffer target, int start, int payloadBytes, CRC32C checksum) {
    return target != null && !target.isReadOnly() && checksum != null && start >= 0
        && payloadBytes > 0 && payloadBytes <= CatalogDefinitionRecordCodec.MAX_PAYLOAD_BYTES
        && start <= target.limit() - CatalogDefinitionRecordCodec.HEADER_BYTES - payloadBytes;
  }

  static boolean valid(
      long recordId, long objectId, long schemaId, long generation, int kind,
      int ordinal, int logicalStart, int logicalCount, int payloadBytes) {
    return recordId > 0 && CatalogKeyspace.validObjectHead(objectId)
        && schemaId > 0 && generation > 0
        && ordinal >= 0 && ordinal < SqlShapeLimits.MAX_SCHEMA_CHUNKS
        && logicalStart >= 0 && logicalCount > 0
        && logicalStart <= Integer.MAX_VALUE - logicalCount
        && payloadBytes > 0 && payloadBytes <= CatalogDefinitionRecordCodec.MAX_PAYLOAD_BYTES
        && validLogicalCount(kind, logicalCount);
  }

  static int recordChecksum(
      ByteBuffer source, int start, int payloadBytes, CRC32C checksum) {
    checksum.reset();
    for (int index = 0; index < CatalogDefinitionRecordCodec.CHECKSUM_OFFSET; index++) {
      checksum.update(source.get(start + index));
    }
    for (int index = 0; index < payloadBytes; index++) {
      checksum.update(source.get(start + CatalogDefinitionRecordCodec.HEADER_BYTES + index));
    }
    return (int) checksum.getValue();
  }

  static void updateChildSetChecksum(CRC32C checksum, int childChecksum) {
    checksum.update(childChecksum);
    checksum.update(childChecksum >>> 8);
    checksum.update(childChecksum >>> 16);
    checksum.update(childChecksum >>> 24);
  }

  private static boolean validLogicalCount(int kind, int count) {
    return switch (kind) {
      case CatalogDefinitionRecordCodec.KIND_COLUMNS ->
          count <= CatalogDefinitionRecordCodec.MAX_COLUMN_RECORDS;
      case CatalogDefinitionRecordCodec.KIND_KEY ->
          count <= SqlShapeLimits.MAX_TABLE_KEY_PARTS;
      case CatalogDefinitionRecordCodec.KIND_CONSTRAINT,
          CatalogDefinitionRecordCodec.KIND_EXPRESSION ->
          kind == CatalogDefinitionRecordCodec.KIND_EXPRESSION
              ? count <= CatalogDefinitionRecordCodec.MAX_EXPRESSION_NODES
              : count <= SqlShapeLimits.MAX_CHECK_CONSTRAINTS;
      case CatalogDefinitionRecordCodec.KIND_STATISTICS ->
          count <= CatalogDefinitionRecordCodec.MAX_STATISTICS_COLUMNS;
      default -> false;
    };
  }
}
