package io.riverdb.format.catalog;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.FormatBytes;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

/** Byte-exact bounded continuation row for schema, constraint, and statistics payloads. */
public final class CatalogSegmentCodec {
  public static final int HEADER_BYTES = 48;
  public static final int MAXIMUM_RECORD_BYTES = 4_096;
  public static final int MAXIMUM_PAYLOAD_BYTES = MAXIMUM_RECORD_BYTES - HEADER_BYTES;
  public static final int VERSION = 1;
  public static final int KIND_SCHEMA = 1;
  public static final int KIND_CONSTRAINT = 2;
  public static final int KIND_STATISTICS = 3;

  private static final long MAGIC = 0x5249564341545347L; // RIVCATSG
  static final int CHECKSUM_OFFSET = 40;
  static final int COMPLEMENT_OFFSET = 44;

  private CatalogSegmentCodec() {
  }

  public static StatusCode encode(
      ByteBuffer target,
      int kind,
      int tableId,
      int ordinal,
      int segmentCount,
      long generation,
      ByteBuffer payload,
      CRC32C checksum) {
    int bytes = payload == null ? -1 : payload.remaining();
    if (target == null
        || payload == null
        || checksum == null
        || target.isReadOnly()
        || target.remaining() < HEADER_BYTES + bytes
        || !validKind(kind)
        || tableId <= 0
        || tableId > CatalogHeaderCodec.MAXIMUM_TABLE_ID
        || ordinal < 0
        || ordinal >= segmentCount
        || segmentCount <= 0
        || segmentCount > CatalogHeaderCodec.MAXIMUM_SEGMENTS
        || generation <= 0
        || bytes <= 0
        || bytes > MAXIMUM_PAYLOAD_BYTES) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int start = target.position();
    FormatBytes.putLong(target, start, MAGIC);
    FormatBytes.putInt(target, start + 8, VERSION);
    FormatBytes.putInt(target, start + 12, kind);
    FormatBytes.putInt(target, start + 16, tableId);
    FormatBytes.putInt(target, start + 20, ordinal);
    FormatBytes.putInt(target, start + 24, segmentCount);
    FormatBytes.putInt(target, start + 28, bytes);
    FormatBytes.putLong(target, start + 32, generation);
    int payloadStart = payload.position();
    for (int index = 0; index < bytes; index++) {
      target.put(start + HEADER_BYTES + index, payload.get(payloadStart + index));
    }
    int value = checksum(target, start, bytes, checksum);
    FormatBytes.putInt(target, start + CHECKSUM_OFFSET, value);
    FormatBytes.putInt(target, start + COMPLEMENT_OFFSET, ~value);
    target.limit(start + HEADER_BYTES + bytes);
    return StatusCode.OK;
  }

  public static StatusCode decode(
      ByteBuffer source, CatalogSegment result, CRC32C checksum) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (source == null || checksum == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (source.remaining() < HEADER_BYTES) return StatusCode.CORRUPTION;
    int start = source.position();
    int bytes = FormatBytes.getInt(source, start + 28);
    int stored = FormatBytes.getInt(source, start + CHECKSUM_OFFSET);
    if (bytes <= 0
        || bytes > MAXIMUM_PAYLOAD_BYTES
        || source.remaining() != HEADER_BYTES + bytes
        || FormatBytes.getLong(source, start) != MAGIC
        || FormatBytes.getInt(source, start + 8) != VERSION
        || FormatBytes.getInt(source, start + COMPLEMENT_OFFSET) != ~stored
        || checksum(source, start, bytes, checksum) != stored) {
      return StatusCode.CORRUPTION;
    }
    int kind = FormatBytes.getInt(source, start + 12);
    int tableId = FormatBytes.getInt(source, start + 16);
    int ordinal = FormatBytes.getInt(source, start + 20);
    int count = FormatBytes.getInt(source, start + 24);
    long generation = FormatBytes.getLong(source, start + 32);
    if (!validKind(kind)
        || tableId <= 0
        || tableId > CatalogHeaderCodec.MAXIMUM_TABLE_ID
        || ordinal < 0
        || ordinal >= count
        || count <= 0
        || count > CatalogHeaderCodec.MAXIMUM_SEGMENTS
        || generation <= 0) {
      return StatusCode.CORRUPTION;
    }
    result.set(kind, tableId, ordinal, count, bytes, generation);
    return StatusCode.OK;
  }

  private static int checksum(
      ByteBuffer source, int start, int payloadBytes, CRC32C checksum) {
    checksum.reset();
    for (int index = 0; index < CHECKSUM_OFFSET; index++) {
      checksum.update(source.get(start + index));
    }
    for (int index = 0; index < payloadBytes; index++) {
      checksum.update(source.get(start + HEADER_BYTES + index));
    }
    return (int) checksum.getValue();
  }

  private static boolean validKind(int kind) {
    return kind == KIND_SCHEMA || kind == KIND_CONSTRAINT || kind == KIND_STATISTICS;
  }
}
