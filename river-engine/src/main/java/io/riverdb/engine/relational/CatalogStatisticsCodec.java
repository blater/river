package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/** Canonical fixed record for one table's bounded ANALYZE statistics. */
final class CatalogStatisticsCodec {
  static final int BYTES = 56 + TableSchema.MAXIMUM_COLUMNS * Long.BYTES * 4;
  private static final long MAGIC = 0x5249564552535441L; // RIVERSTA
  private static final int VERSION = 1;
  private static final int NULLS = 56;
  private static final int DISTINCT = NULLS + TableSchema.MAXIMUM_COLUMNS * Long.BYTES;
  private static final int MINIMUM = DISTINCT + TableSchema.MAXIMUM_COLUMNS * Long.BYTES;
  private static final int MAXIMUM = MINIMUM + TableSchema.MAXIMUM_COLUMNS * Long.BYTES;

  private CatalogStatisticsCodec() {
  }

  static void encode(ByteBuffer target, TableStatistics source) {
    target.clear();
    for (int index = 0; index < BYTES; index++) target.put(index, (byte) 0);
    target.putLong(0, MAGIC);
    target.putInt(8, VERSION);
    target.putInt(12, source.tableId());
    target.putInt(16, source.columnCount());
    target.putInt(20, 0);
    target.putLong(24, source.epoch());
    target.putLong(32, source.rowCount());
    target.putLong(40, source.minMaxMask());
    target.putLong(48, source.sampledMask());
    for (int column = 0; column < source.columnCount(); column++) {
      target.putLong(NULLS + column * Long.BYTES, source.nullCount(column));
      target.putLong(DISTINCT + column * Long.BYTES, source.distinctCount(column));
      target.putLong(MINIMUM + column * Long.BYTES, source.minimumValue(column));
      target.putLong(MAXIMUM + column * Long.BYTES, source.maximumValue(column));
    }
    target.position(0);
    target.limit(BYTES);
  }

  static StatusCode decode(
      HeapRowResult source,
      ByteBuffer scratch,
      TableDefinition table,
      TableStatistics result) {
    result.reset();
    scratch.clear();
    StatusCode status = source.copyTo(scratch);
    if (!status.isOk()) return status;
    if (source.length() != BYTES
        || scratch.getLong(0) != MAGIC
        || scratch.getInt(8) != VERSION
        || scratch.getInt(12) != table.tableId()
        || scratch.getInt(16) != table.columnCount()
        || scratch.getInt(20) != 0) return StatusCode.CORRUPTION;
    int columns = scratch.getInt(16);
    long epoch = scratch.getLong(24);
    long rows = scratch.getLong(32);
    long mask = (1L << columns) - 1;
    long minMaxMask = scratch.getLong(40);
    long sampledMask = scratch.getLong(48);
    if (epoch < 0 || rows < 0
        || (minMaxMask & ~mask) != 0
        || (sampledMask & ~mask) != 0) return StatusCode.CORRUPTION;
    result.begin(table.tableId(), columns, epoch);
    result.setRowCount(rows);
    for (int column = 0; column < TableSchema.MAXIMUM_COLUMNS; column++) {
      long nulls = scratch.getLong(NULLS + column * Long.BYTES);
      long distinct = scratch.getLong(DISTINCT + column * Long.BYTES);
      long minimum = scratch.getLong(MINIMUM + column * Long.BYTES);
      long maximum = scratch.getLong(MAXIMUM + column * Long.BYTES);
      boolean used = column < columns;
      boolean hasRange = (minMaxMask & 1L << column) != 0;
      long nonNull = rows - nulls;
      if (!used && (nulls != 0 || distinct != 0 || minimum != 0 || maximum != 0)
          || used && (nulls < 0 || nulls > rows
              || distinct < 0 || distinct > nonNull
              || (distinct == 0) != (nonNull == 0)
              || nonNull == 0 && hasRange)
          || !hasRange && (minimum != 0 || maximum != 0)) {
        result.reset();
        return StatusCode.CORRUPTION;
      }
      if (used) {
        result.setColumn(
            column,
            nulls,
            distinct,
            (sampledMask & 1L << column) != 0,
            hasRange,
            minimum,
            maximum);
      }
    }
    return StatusCode.OK;
  }
}
