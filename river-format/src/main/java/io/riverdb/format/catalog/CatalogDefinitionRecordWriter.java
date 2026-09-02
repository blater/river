package io.riverdb.format.catalog;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.FormatBytes;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

final class CatalogDefinitionRecordWriter {
  private CatalogDefinitionRecordWriter() {
  }

  static StatusCode encode(
      ByteBuffer target, int start, long recordId, long objectId, long schemaId,
      long generation, int kind, int ordinal, int logicalStart, int logicalCount,
      ByteBuffer payload, CRC32C checksum) {
    int payloadBytes = payload == null ? -1 : payload.remaining();
    if (!CatalogDefinitionRecordFormat.writable(target, start, payloadBytes, checksum)
        || !CatalogDefinitionRecordFormat.valid(recordId, objectId, schemaId, generation,
            kind, ordinal, logicalStart, logicalCount, payloadBytes)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    writeHeader(target, start, recordId, objectId, schemaId, generation,
        kind, ordinal, logicalStart, logicalCount, payloadBytes);
    int payloadStart = payload.position();
    for (int index = 0; index < payloadBytes; index++) {
      target.put(start + CatalogDefinitionRecordCodec.HEADER_BYTES + index,
          payload.get(payloadStart + index));
    }
    int value = CatalogDefinitionRecordFormat.recordChecksum(
        target, start, payloadBytes, checksum);
    FormatBytes.putInt(target, start + CatalogDefinitionRecordCodec.CHECKSUM_OFFSET, value);
    FormatBytes.putInt(target, start + CatalogDefinitionRecordCodec.COMPLEMENT_OFFSET, ~value);
    return StatusCode.OK;
  }

  private static void writeHeader(
      ByteBuffer target, int start, long recordId, long objectId, long schemaId,
      long generation, int kind, int ordinal, int logicalStart, int logicalCount,
      int payloadBytes) {
    FormatBytes.putLong(target, start, CatalogDefinitionRecordCodec.MAGIC);
    FormatBytes.putInt(target, start + 8, CatalogDefinitionRecordCodec.VERSION);
    FormatBytes.putInt(target, start + 12, CatalogDefinitionRecordCodec.HEADER_BYTES);
    FormatBytes.putLong(target, start + 16, recordId);
    FormatBytes.putLong(target, start + 24, objectId);
    FormatBytes.putLong(target, start + 32, schemaId);
    FormatBytes.putLong(target, start + 40, generation);
    FormatBytes.putInt(target, start + 48, kind);
    FormatBytes.putInt(target, start + 52, ordinal);
    FormatBytes.putInt(target, start + 56, logicalStart);
    FormatBytes.putInt(target, start + 60, logicalCount);
    FormatBytes.putInt(target, start + 64, payloadBytes);
    FormatBytes.putInt(target, start + 68, 0);
  }
}
