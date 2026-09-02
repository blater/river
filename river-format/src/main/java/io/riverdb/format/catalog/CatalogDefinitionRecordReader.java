package io.riverdb.format.catalog;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.FormatBytes;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

final class CatalogDefinitionRecordReader {
  private CatalogDefinitionRecordReader() {
  }

  static StatusCode decode(
      ByteBuffer source, int start, int recordBytes, CatalogDefinitionRecord result,
      CRC32C checksum) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (source == null || checksum == null || start < 0 || recordBytes < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (recordBytes < CatalogDefinitionRecordCodec.HEADER_BYTES
        || recordBytes > CatalogDefinitionRecordCodec.MAX_RECORD_BYTES
        || start > source.limit() - recordBytes) return StatusCode.CORRUPTION;
    int payloadBytes = FormatBytes.getInt(source, start + 64);
    int stored = FormatBytes.getInt(
        source, start + CatalogDefinitionRecordCodec.CHECKSUM_OFFSET);
    if (!validEnvelope(source, start, recordBytes, payloadBytes, stored, checksum)) {
      return StatusCode.CORRUPTION;
    }
    return decodeFields(source, start, payloadBytes, stored, result);
  }

  private static StatusCode decodeFields(
      ByteBuffer source, int start, int payloadBytes, int stored,
      CatalogDefinitionRecord result) {
    long recordId = FormatBytes.getLong(source, start + 16);
    long objectId = FormatBytes.getLong(source, start + 24);
    long schemaId = FormatBytes.getLong(source, start + 32);
    long generation = FormatBytes.getLong(source, start + 40);
    int kind = FormatBytes.getInt(source, start + 48);
    int ordinal = FormatBytes.getInt(source, start + 52);
    int logicalStart = FormatBytes.getInt(source, start + 56);
    int logicalCount = FormatBytes.getInt(source, start + 60);
    if (!CatalogDefinitionRecordFormat.valid(recordId, objectId, schemaId, generation,
        kind, ordinal, logicalStart, logicalCount, payloadBytes)) {
      return StatusCode.CORRUPTION;
    }
    result.set(recordId, objectId, schemaId, generation, kind, ordinal,
        logicalStart, logicalCount, payloadBytes, stored);
    return StatusCode.OK;
  }

  private static boolean validEnvelope(
      ByteBuffer source, int start, int recordBytes, int payloadBytes,
      int stored, CRC32C checksum) {
    return payloadBytes > 0
        && recordBytes == CatalogDefinitionRecordCodec.HEADER_BYTES + payloadBytes
        && FormatBytes.getLong(source, start) == CatalogDefinitionRecordCodec.MAGIC
        && FormatBytes.getInt(source, start + 8) == CatalogDefinitionRecordCodec.VERSION
        && FormatBytes.getInt(source, start + 12) == CatalogDefinitionRecordCodec.HEADER_BYTES
        && FormatBytes.getInt(source, start + 68) == 0
        && FormatBytes.getInt(
            source, start + CatalogDefinitionRecordCodec.COMPLEMENT_OFFSET) == ~stored
        && CatalogDefinitionRecordFormat.recordChecksum(
            source, start, payloadBytes, checksum) == stored;
  }
}
