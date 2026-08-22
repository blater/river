package io.riverdb.format.catalog;

import io.riverdb.base.column.ColumnSet;
import io.riverdb.base.error.StatusCode;
import io.riverdb.format.FormatBytes;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

/** Byte-exact header for atomically published segmented catalog records. */
public final class CatalogHeaderCodec {
  public static final int BYTES = 64;
  public static final int VERSION = 1;
  public static final int KIND_TABLE = 1;
  public static final int KIND_STATISTICS = 2;
  public static final int KEYLESS = 0;
  public static final int SIMPLE_KEY = 1;
  public static final int COMPOSITE_KEY = 2;
  public static final int MAXIMUM_KEY_ARITY = 4;
  public static final int MAXIMUM_SEGMENTS = 32;
  public static final int MAXIMUM_TABLE_ID = 0x7fff;

  private static final long MAGIC = 0x5249564341544844L; // RIVCATHD
  static final int CHECKSUM_OFFSET = 56;
  static final int COMPLEMENT_OFFSET = 60;

  private CatalogHeaderCodec() {
  }

  public static StatusCode encode(
      ByteBuffer target,
      int kind,
      int tableId,
      int keyKind,
      int keyArity,
      int columnCount,
      long generation,
      long firstSegmentKey,
      int segmentCount,
      int payloadBytes,
      CRC32C checksum) {
    if (!valid(target, kind, tableId, keyKind, keyArity, columnCount, generation,
        firstSegmentKey, segmentCount, payloadBytes, checksum)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int start = target.position();
    FormatBytes.putLong(target, start, MAGIC);
    FormatBytes.putInt(target, start + 8, VERSION);
    FormatBytes.putInt(target, start + 12, kind);
    FormatBytes.putInt(target, start + 16, tableId);
    FormatBytes.putInt(target, start + 20, keyKind);
    FormatBytes.putInt(target, start + 24, keyArity);
    FormatBytes.putInt(target, start + 28, columnCount);
    FormatBytes.putLong(target, start + 32, generation);
    FormatBytes.putLong(target, start + 40, firstSegmentKey);
    FormatBytes.putInt(target, start + 48, segmentCount);
    FormatBytes.putInt(target, start + 52, payloadBytes);
    int value = FormatBytes.checksum(target, start, CHECKSUM_OFFSET, checksum);
    FormatBytes.putInt(target, start + CHECKSUM_OFFSET, value);
    FormatBytes.putInt(target, start + COMPLEMENT_OFFSET, ~value);
    target.limit(start + BYTES);
    return StatusCode.OK;
  }

  public static StatusCode decode(
      ByteBuffer source, CatalogHeader result, CRC32C checksum) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (source == null || checksum == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (source.remaining() != BYTES) return StatusCode.CORRUPTION;
    int start = source.position();
    int stored = FormatBytes.getInt(source, start + CHECKSUM_OFFSET);
    if (FormatBytes.getLong(source, start) != MAGIC
        || FormatBytes.getInt(source, start + 8) != VERSION
        || FormatBytes.getInt(source, start + COMPLEMENT_OFFSET) != ~stored
        || FormatBytes.checksum(source, start, CHECKSUM_OFFSET, checksum) != stored) {
      return StatusCode.CORRUPTION;
    }
    int kind = FormatBytes.getInt(source, start + 12);
    int tableId = FormatBytes.getInt(source, start + 16);
    int keyKind = FormatBytes.getInt(source, start + 20);
    int keyArity = FormatBytes.getInt(source, start + 24);
    int columnCount = FormatBytes.getInt(source, start + 28);
    long generation = FormatBytes.getLong(source, start + 32);
    long firstKey = FormatBytes.getLong(source, start + 40);
    int segments = FormatBytes.getInt(source, start + 48);
    int bytes = FormatBytes.getInt(source, start + 52);
    if (!validFields(kind, tableId, keyKind, keyArity, columnCount, generation,
        firstKey, segments, bytes)) {
      return StatusCode.CORRUPTION;
    }
    result.set(kind, tableId, keyKind, keyArity, columnCount, generation,
        firstKey, segments, bytes);
    return StatusCode.OK;
  }

  private static boolean valid(
      ByteBuffer target,
      int kind,
      int tableId,
      int keyKind,
      int keyArity,
      int columnCount,
      long generation,
      long firstSegmentKey,
      int segmentCount,
      int payloadBytes,
      CRC32C checksum) {
    return target != null
        && !target.isReadOnly()
        && target.remaining() >= BYTES
        && checksum != null
        && validFields(kind, tableId, keyKind, keyArity, columnCount, generation,
            firstSegmentKey, segmentCount, payloadBytes);
  }

  private static boolean validFields(
      int kind,
      int tableId,
      int keyKind,
      int keyArity,
      int columnCount,
      long generation,
      long firstSegmentKey,
      int segmentCount,
      int payloadBytes) {
    return (kind == KIND_TABLE || kind == KIND_STATISTICS)
        && tableId > 0
        && tableId <= MAXIMUM_TABLE_ID
        && validKey(keyKind, keyArity)
        && keyArity <= columnCount
        && columnCount > 0
        && columnCount <= ColumnSet.MAXIMUM_COLUMNS
        && generation > 0
        && segmentCount <= MAXIMUM_SEGMENTS
        && CatalogContinuationKey.validRange(firstSegmentKey, segmentCount)
        && payloadBytes >= segmentCount
        && payloadBytes <= (long) segmentCount * CatalogSegmentCodec.MAXIMUM_PAYLOAD_BYTES;
  }

  private static boolean validKey(int kind, int arity) {
    return switch (kind) {
      case KEYLESS -> arity == 0;
      case SIMPLE_KEY -> arity == 1;
      case COMPOSITE_KEY -> arity >= 2 && arity <= MAXIMUM_KEY_ARITY;
      default -> false;
    };
  }

}
