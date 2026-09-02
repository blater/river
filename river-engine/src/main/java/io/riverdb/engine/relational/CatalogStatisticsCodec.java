package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.FormatBytes;
import java.nio.ByteBuffer;

/** Canonical little-endian payload for one bounded statistics child record. */
final class CatalogStatisticsCodec {
  static final long MAGIC = 0x5249564552535443L; // RIVERSTC
  static final int VERSION = 3;
  static final int HEADER_BYTES = 32;
  static final int COLUMN_BYTES = 33;
  private static final int SAMPLED = 1;
  private static final int MIN_MAX = 2;

  private CatalogStatisticsCodec() { }

  static int payloadBytes(int columns) {
    return HEADER_BYTES + columns * COLUMN_BYTES;
  }

  static StatusCode encode(
      ByteBuffer target, TableStatistics source, int firstColumn, int columns) {
    int bytes = payloadBytes(columns);
    if (target == null || target.isReadOnly() || source == null
        || firstColumn < 0 || columns <= 0
        || firstColumn > source.columnCount() - columns
        || target.capacity() < bytes) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    target.clear();
    target.limit(bytes);
    FormatBytes.putLong(target, 0, MAGIC);
    FormatBytes.putInt(target, 8, VERSION);
    FormatBytes.putInt(target, 12, 0);
    FormatBytes.putLong(target, 16, source.epoch());
    FormatBytes.putLong(target, 24, source.rowCount());
    int offset = HEADER_BYTES;
    for (int index = 0; index < columns; index++) {
      int column = firstColumn + index;
      int flags = (source.sampled(column) ? SAMPLED : 0)
          | (source.hasMinMax(column) ? MIN_MAX : 0);
      target.put(offset, (byte) flags);
      FormatBytes.putLong(target, offset + 1, source.nullCount(column));
      FormatBytes.putLong(target, offset + 9, source.distinctCount(column));
      FormatBytes.putLong(target, offset + 17, source.minimumValue(column));
      FormatBytes.putLong(target, offset + 25, source.maximumValue(column));
      offset += COLUMN_BYTES;
    }
    target.position(0);
    return StatusCode.OK;
  }

  static StatusCode decode(
      ByteBuffer source, int start, int bytes, TableDefinition table,
      int firstColumn, int columns, TableStatistics result) {
    if (!validEnvelope(source, start, bytes, firstColumn, columns, table)) {
      return StatusCode.CORRUPTION;
    }
    long epoch = FormatBytes.getLong(source, start + 16);
    long rows = FormatBytes.getLong(source, start + 24);
    if (epoch < 0 || rows < 0) return StatusCode.CORRUPTION;
    StatusCode status = firstColumn == 0
        ? result.begin(table.tableId(), table.columnCount(), epoch)
        : matchingSnapshot(result, table, epoch, rows);
    if (!status.isOk()) return status;
    if (firstColumn == 0) result.setRowCount(rows);
    int offset = start + HEADER_BYTES;
    for (int index = 0; index < columns; index++) {
      status = decodeColumn(source, offset, firstColumn + index, rows, result);
      if (!status.isOk()) return status;
      offset += COLUMN_BYTES;
    }
    return StatusCode.OK;
  }

  private static StatusCode decodeColumn(
      ByteBuffer source, int offset, int column, long rows, TableStatistics result) {
    int flags = Byte.toUnsignedInt(source.get(offset));
    long nulls = FormatBytes.getLong(source, offset + 1);
    long distinct = FormatBytes.getLong(source, offset + 9);
    long minimum = FormatBytes.getLong(source, offset + 17);
    long maximum = FormatBytes.getLong(source, offset + 25);
    boolean sampled = (flags & SAMPLED) != 0;
    boolean range = (flags & MIN_MAX) != 0;
    long nonNull = rows - nulls;
    if ((flags & ~(SAMPLED | MIN_MAX)) != 0 || nulls < 0 || nulls > rows
        || distinct < 0 || distinct > nonNull
        || (distinct == 0) != (nonNull == 0) || nonNull == 0 && range
        || !range && (minimum != 0 || maximum != 0)) {
      return StatusCode.CORRUPTION;
    }
    result.setColumn(column, nulls, distinct, sampled, range, minimum, maximum);
    return StatusCode.OK;
  }

  private static boolean validEnvelope(
      ByteBuffer source, int start, int bytes,
      int first, int columns, TableDefinition table) {
    return source != null && table != null && start >= 0 && columns > 0 && first >= 0
        && first <= table.columnCount() - columns && bytes == payloadBytes(columns)
        && start <= source.limit() - bytes
        && FormatBytes.getLong(source, start) == MAGIC
        && FormatBytes.getInt(source, start + 8) == VERSION
        && FormatBytes.getInt(source, start + 12) == 0;
  }

  private static StatusCode matchingSnapshot(
      TableStatistics result, TableDefinition table, long epoch, long rows) {
    return result.availableFor(table) && result.epoch() == epoch && result.rowCount() == rows
        ? StatusCode.OK : StatusCode.CORRUPTION;
  }
}
