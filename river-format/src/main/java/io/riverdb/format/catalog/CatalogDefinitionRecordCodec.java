package io.riverdb.format.catalog;

import io.riverdb.base.error.StatusCode;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

/** Self-identifying v2 child record with a bounded payload and canonical little-endian header. */
public final class CatalogDefinitionRecordCodec {
  public static final int VERSION = 2;
  public static final int HEADER_BYTES = 80;
  public static final int MAX_RECORD_BYTES = 8_192;
  public static final int MAX_PAYLOAD_BYTES = MAX_RECORD_BYTES - HEADER_BYTES;
  public static final int MAX_COLUMN_RECORDS = 32;
  public static final int MAX_STATISTICS_COLUMNS = 128;
  public static final int MAX_EXPRESSION_NODES = 1_024;

  public static final int KIND_COLUMNS = 1;
  public static final int KIND_KEY = 2;
  public static final int KIND_CONSTRAINT = 3;
  public static final int KIND_EXPRESSION = 4;
  public static final int KIND_STATISTICS = 5;

  static final long MAGIC = 0x5249564341544348L; // RIVCATCH
  static final int CHECKSUM_OFFSET = 72;
  static final int COMPLEMENT_OFFSET = 76;

  private CatalogDefinitionRecordCodec() {
  }

  public static StatusCode encode(
      ByteBuffer target,
      int start,
      long catalogRecordId,
      long objectId,
      long schemaId,
      long catalogGeneration,
      int kind,
      int ordinal,
      int logicalStart,
      int logicalCount,
      ByteBuffer payload,
      CRC32C checksum) {
    return CatalogDefinitionRecordWriter.encode(target, start, catalogRecordId, objectId,
        schemaId, catalogGeneration, kind, ordinal, logicalStart, logicalCount,
        payload, checksum);
  }

  public static StatusCode decode(
      ByteBuffer source,
      int start,
      int recordBytes,
      CatalogDefinitionRecord result,
      CRC32C checksum) {
    return CatalogDefinitionRecordReader.decode(
        source, start, recordBytes, result, checksum);
  }

  public static StatusCode decode(
      ByteBuffer source, int start, CatalogDefinitionRecord result, CRC32C checksum) {
    if (source == null || start < 0) {
      if (result != null) result.reset();
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return decode(source, start, source.limit() - start, result, checksum);
  }

  /** Adds one decoded child checksum to the canonical complete-child checksum stream. */
  public static void updateChildSetChecksum(CRC32C checksum, int childChecksum) {
    CatalogDefinitionRecordFormat.updateChildSetChecksum(checksum, childChecksum);
  }
}
