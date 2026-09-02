package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalScanCursor;
import io.riverdb.engine.relational.RelationalScanResult;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.engine.relational.TableStatistics;

/** Bounded allocation-free table scan producing durable planner statistics. */
final class SqlAnalyzeTableExecution {
  private final RelationalSession session;
  private final TableDefinition table = new TableDefinition();
  private final TableStatistics statistics = new TableStatistics();
  private final RelationalScanCursor cursor = new RelationalScanCursor();
  private final RelationalScanResult scan = new RelationalScanResult();
  private final SqlAnalyzeWorkspace workspace;
  private final SqlBlockPhysicalRowReader reader;
  private final SqlBlockRow row;
  private final SqlExpressionEvaluator expressions = new SqlExpressionEvaluator();
  private final SqlDescriptorAnalyzeScan descriptorScan;
  private SqlBlockRow currentRow;
  private int rowCount;
  private boolean descriptor;

  SqlAnalyzeTableExecution(RelationalSession relationalSession) {
    this(relationalSession, SqlRetainedArrayAllocator.STANDARD);
  }

  SqlAnalyzeTableExecution(
      RelationalSession relationalSession, SqlRetainedArrayAllocator allocator) {
    session = relationalSession;
    workspace = new SqlAnalyzeWorkspace(allocator);
    reader = new SqlBlockPhysicalRowReader(allocator);
    row = new SqlBlockRow(allocator);
    descriptorScan = new SqlDescriptorAnalyzeScan(session);
  }

  StatusCode analyze(CharSequence tableName) {
    reset();
    StatusCode status = descriptorScan.resolve(tableName, table);
    descriptor = status.isOk();
    if (status == StatusCode.CONFLICT) status = session.resolveTable(tableName, table);
    if (status.isOk()) status = prepareRow();
    if (status.isOk()) status = descriptor
        ? descriptorScan.begin() : session.beginScan(table, cursor);
    while (status.isOk()) {
      status = descriptor ? descriptorScan.next() : session.nextScan(cursor, scan);
      if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
        break;
      }
      if (!status.isOk()) break;
      if (!descriptor) status = reader.read(scan.key(), scan.row(), table, row);
      if (status.isOk()) {
        currentRow = descriptor ? descriptorScan.row() : row;
        accumulate();
      }
      scan.reset();
    }
    StatusCode runtime = status;
    StatusCode closed = descriptor ? descriptorScan.reset()
        : cursor.isActive() ? session.closeScan(cursor) : StatusCode.OK;
    if (!runtime.isOk()) return runtime;
    if (!closed.isOk()) return closed;
    StatusCode published = publish();
    StatusCode written = published.isOk()
        ? session.writeStatistics(table, statistics) : published;
    eraseScratch();
    return written;
  }

  int rowCount() { return rowCount; }

  boolean hasResources() { return cursor.isActive() || descriptorScan.hasResources(); }

  StatusCode closeResources() {
    if (descriptorScan.hasResources()) return descriptorScan.reset();
    if (!cursor.isActive()) return StatusCode.OK;
    StatusCode status = session.closeScan(cursor);
    if (status.isOk()) eraseScratch();
    return status;
  }

  private void accumulate() {
    rowCount++;
    for (int column = 0; column < table.columnCount(); column++) {
      if (currentRow.nullValue(column)) {
        workspace.nullCounts[column]++;
        continue;
      }
      long hash = hash(column);
      int count = Short.toUnsignedInt(workspace.distinctCounts[column]);
      if (count < SqlAnalyzeWorkspace.DISTINCT_SLOTS
          && addDistinct(column, count, hash)) {
        workspace.distinctCounts[column] = (short) (count + 1);
      } else if (count >= SqlAnalyzeWorkspace.DISTINCT_SLOTS) {
        workspace.sampled[column] = true;
      }
      if (!table.isVarchar(column)) accumulateRange(column);
    }
  }

  private boolean addDistinct(int column, int count, long hash) {
    int offset = column * SqlAnalyzeWorkspace.DISTINCT_SLOTS;
    for (int index = 0; index < count; index++) {
      if (workspace.distinctValues[offset + index] == hash) return false;
    }
    workspace.distinctValues[offset + count] = hash;
    return true;
  }

  private void accumulateRange(int column) {
    long value = currentRow.value(column);
    if (!workspace.minMax[column]) {
      workspace.minimumValues[column] = value;
      workspace.maximumValues[column] = value;
      workspace.minMax[column] = true;
      return;
    }
    int descriptor = table.typeDescriptor(column);
    if (expressions.compareExact(
        value, descriptor, workspace.minimumValues[column], descriptor) < 0) {
      workspace.minimumValues[column] = value;
    }
    if (expressions.compareExact(
        value, descriptor, workspace.maximumValues[column], descriptor) > 0) {
      workspace.maximumValues[column] = value;
    }
  }

  private long hash(int column) {
    long hash = 0xcbf29ce484222325L;
    if (table.isVarchar(column)) {
      for (int index = 0; index < currentRow.textLength(column); index++) {
        hash ^= currentRow.textCharacter(column, index);
        hash *= 0x100000001b3L;
      }
    } else {
      long value = currentRow.value(column);
      for (int shift = 0; shift < Long.SIZE; shift += Byte.SIZE) {
        hash ^= value >>> shift & 0xff;
        hash *= 0x100000001b3L;
      }
    }
    return hash == 0 ? 1 : hash;
  }

  private StatusCode publish() {
    statistics.setRowCount(rowCount);
    for (int column = 0; column < table.columnCount(); column++) {
      statistics.setColumn(
          column,
          workspace.nullCounts[column],
          Short.toUnsignedInt(workspace.distinctCounts[column]),
          workspace.sampled[column],
          workspace.minMax[column],
          workspace.minimumValues[column],
          workspace.maximumValues[column]);
    }
    return StatusCode.OK;
  }

  private StatusCode prepareRow() {
    int columns = table.columnCount();
    StatusCode status = statistics.begin(
        table.tableId(), columns, session.visibleCommitSequence());
    if (!status.isOk()) return status;
    status = workspace.reserve(columns);
    return status.isOk() && !descriptor ? reader.prepare(table, row) : status;
  }

  private void reset() {
    eraseScratch();
    table.reset();
    statistics.reset();
    cursor.reset();
    scan.reset();
    reader.reset();
    descriptorScan.reset();
    currentRow = null;
    descriptor = false;
    rowCount = 0;
  }

  private void eraseScratch() {
    for (int column = 0; column < workspace.distinctCounts.length; column++) {
      int count = Short.toUnsignedInt(workspace.distinctCounts[column]);
      int offset = column * SqlAnalyzeWorkspace.DISTINCT_SLOTS;
      for (int index = 0; index < count; index++) {
        workspace.distinctValues[offset + index] = 0;
      }
      workspace.distinctCounts[column] = 0;
      workspace.nullCounts[column] = 0;
      workspace.minimumValues[column] = 0;
      workspace.maximumValues[column] = 0;
      workspace.minMax[column] = false;
      workspace.sampled[column] = false;
    }
    scan.reset();
    reader.reset();
    row.reset(0);
    currentRow = null;
  }
}
