package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalScanCursor;
import io.riverdb.engine.relational.RelationalScanResult;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.engine.relational.TableSchema;
import io.riverdb.engine.relational.TableStatistics;

/** Bounded allocation-free table scan producing durable planner statistics. */
final class SqlAnalyzeTableExecution {
  private static final int MAXIMUM_DISTINCT = 1_024;
  private final RelationalSession session;
  private final TableDefinition table = new TableDefinition();
  private final TableStatistics statistics = new TableStatistics();
  private final RelationalScanCursor cursor = new RelationalScanCursor();
  private final RelationalScanResult scan = new RelationalScanResult();
  private final SqlBlockPhysicalRowReader reader = new SqlBlockPhysicalRowReader();
  private final SqlBlockRow row = new SqlBlockRow();
  private final SqlExpressionEvaluator expressions = new SqlExpressionEvaluator();
  private final long[] distinctValues =
      new long[TableSchema.MAXIMUM_COLUMNS * MAXIMUM_DISTINCT];
  private final short[] distinctCounts = new short[TableSchema.MAXIMUM_COLUMNS];
  private final long[] nullCounts = new long[TableSchema.MAXIMUM_COLUMNS];
  private final long[] minimumValues = new long[TableSchema.MAXIMUM_COLUMNS];
  private final long[] maximumValues = new long[TableSchema.MAXIMUM_COLUMNS];
  private long minMaxMask;
  private long sampledMask;
  private int rowCount;

  SqlAnalyzeTableExecution(RelationalSession relationalSession) {
    session = relationalSession;
  }

  StatusCode analyze(CharSequence tableName) {
    reset();
    StatusCode status = session.resolveTable(tableName, table);
    if (status.isOk()) prepareRow();
    if (status.isOk()) status = session.beginScan(table, cursor);
    while (status.isOk()) {
      status = session.nextScan(cursor, scan);
      if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
        break;
      }
      if (!status.isOk()) break;
      status = reader.read(scan.key(), scan.row(), table, row);
      if (status.isOk()) accumulate();
      scan.reset();
    }
    StatusCode runtime = status;
    StatusCode closed = cursor.isActive()
        ? session.closeScan(cursor) : StatusCode.OK;
    if (!runtime.isOk()) return runtime;
    if (!closed.isOk()) return closed;
    publish();
    StatusCode written = session.writeStatistics(table, statistics);
    eraseScratch();
    return written;
  }

  int rowCount() { return rowCount; }

  boolean hasResources() { return cursor.isActive(); }

  StatusCode closeResources() {
    if (!cursor.isActive()) return StatusCode.OK;
    StatusCode status = session.closeScan(cursor);
    if (status.isOk()) eraseScratch();
    return status;
  }

  private void accumulate() {
    rowCount++;
    for (int column = 0; column < table.columnCount(); column++) {
      if (row.nullValue(column)) {
        nullCounts[column]++;
        continue;
      }
      long hash = hash(column);
      int count = Short.toUnsignedInt(distinctCounts[column]);
      if (count < MAXIMUM_DISTINCT && addDistinct(column, count, hash)) {
        distinctCounts[column] = (short) (count + 1);
      } else if (count >= MAXIMUM_DISTINCT) {
        sampledMask |= 1L << column;
      }
      if (!table.isVarchar(column)) accumulateRange(column);
    }
  }

  private boolean addDistinct(int column, int count, long hash) {
    int offset = column * MAXIMUM_DISTINCT;
    for (int index = 0; index < count; index++) {
      if (distinctValues[offset + index] == hash) return false;
    }
    distinctValues[offset + count] = hash;
    return true;
  }

  private void accumulateRange(int column) {
    long value = row.value(column);
    long bit = 1L << column;
    if ((minMaxMask & bit) == 0) {
      minimumValues[column] = value;
      maximumValues[column] = value;
      minMaxMask |= bit;
      return;
    }
    int descriptor = table.typeDescriptor(column);
    if (expressions.compareExact(value, descriptor, minimumValues[column], descriptor) < 0) {
      minimumValues[column] = value;
    }
    if (expressions.compareExact(value, descriptor, maximumValues[column], descriptor) > 0) {
      maximumValues[column] = value;
    }
  }

  private long hash(int column) {
    long hash = 0xcbf29ce484222325L;
    if (table.isVarchar(column)) {
      for (int index = 0; index < row.textLength(column); index++) {
        hash ^= row.textCharacter(column, index);
        hash *= 0x100000001b3L;
      }
    } else {
      long value = row.value(column);
      for (int shift = 0; shift < Long.SIZE; shift += Byte.SIZE) {
        hash ^= value >>> shift & 0xff;
        hash *= 0x100000001b3L;
      }
    }
    return hash == 0 ? 1 : hash;
  }

  private void publish() {
    statistics.begin(table.tableId(), table.columnCount(), session.visibleCommitSequence());
    statistics.setRowCount(rowCount);
    for (int column = 0; column < table.columnCount(); column++) {
      statistics.setColumn(
          column,
          nullCounts[column],
          Short.toUnsignedInt(distinctCounts[column]),
          (sampledMask & 1L << column) != 0,
          (minMaxMask & 1L << column) != 0,
          minimumValues[column],
          maximumValues[column]);
    }
  }

  private void prepareRow() {
    row.reset(table.columnCount());
    for (int column = 0; column < table.columnCount(); column++) {
      if (table.isVarchar(column)) row.prepareText(column);
    }
  }

  private void reset() {
    eraseScratch();
    table.reset();
    statistics.reset();
    cursor.reset();
    scan.reset();
    reader.reset();
    minMaxMask = 0;
    sampledMask = 0;
    rowCount = 0;
  }

  private void eraseScratch() {
    for (int column = 0; column < TableSchema.MAXIMUM_COLUMNS; column++) {
      int count = Short.toUnsignedInt(distinctCounts[column]);
      int offset = column * MAXIMUM_DISTINCT;
      for (int index = 0; index < count; index++) distinctValues[offset + index] = 0;
      distinctCounts[column] = 0;
      nullCounts[column] = 0;
      minimumValues[column] = 0;
      maximumValues[column] = 0;
    }
    scan.reset();
    reader.reset();
    row.reset(0);
    minMaxMask = 0;
    sampledMask = 0;
  }
}
