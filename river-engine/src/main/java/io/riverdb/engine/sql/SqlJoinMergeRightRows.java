package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalScanCursor;
import io.riverdb.engine.relational.RelationalScanResult;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.engine.relational.ValueIndexLookupResult;
import io.riverdb.storage.heap.HeapRowResult;

/** Ordered cursor or existing-store sort feeding one merge right input. */
final class SqlJoinMergeRightRows {
  private final RelationalSession session;
  private final SqlBlockRowStore store = new SqlBlockRowStore();
  private final SqlBlockSchema schema = new SqlBlockSchema();
  private final SqlBlockRow right = new SqlBlockRow();
  private final SqlBlockRow candidate = new SqlBlockRow();
  private final SqlBlockPhysicalRowReader reader = new SqlBlockPhysicalRowReader();
  private final SqlBlockPhysicalRowWriter writer = new SqlBlockPhysicalRowWriter();
  private final RelationalScanCursor cursor = new RelationalScanCursor();
  private final RelationalScanResult scan = new RelationalScanResult();
  private final ValueIndexLookupResult indexed = new ValueIndexLookupResult();
  private TableDefinition table;
  private int column = -1;
  private int descriptor;
  private long runValue;
  private int position;
  private int runStart;
  private int runEnd;
  private int runNext;
  private boolean valueIndex;
  private boolean sorted;
  private boolean active;
  private boolean available;
  private boolean finished;
  private boolean runAvailable;
  private boolean probeEmpty;

  SqlJoinMergeRightRows(RelationalSession relationalSession) {
    session = relationalSession;
  }

  StatusCode begin(TableDefinition definition, int keyColumn) {
    StatusCode status = close();
    if (!status.isOk()) return status;
    table = definition;
    column = keyColumn;
    descriptor = table.typeDescriptor(column);
    sorted = column > 0 && !table.hasIndexOn(column);
    valueIndex = !sorted && column > 0;
    prepareSchema();
    writer.prepare();
    status = sorted ? materialize() : valueIndex
        ? session.beginValueScan(table, column, cursor)
        : session.beginScan(table, cursor);
    active = status.isOk();
    return status;
  }

  StatusCode beginProbe(
      long value, int outerDescriptor, SqlExpressionEvaluator expressions) {
    if (!active) return StatusCode.INVALID_EXTERNAL_INPUT;
    probeEmpty = true;
    if (runAvailable && expressions.compareExact(
        value, outerDescriptor, runValue, outerDescriptor) == 0) {
      if (sorted) runNext = runStart; else store.rewind();
      probeEmpty = false;
      return StatusCode.OK;
    }
    runAvailable = false;
    if (sorted) return captureSorted(value, outerDescriptor, expressions);
    while (true) {
      StatusCode status = ensure();
      if (status == StatusCode.CONFLICT) return StatusCode.OK;
      if (!status.isOk()) return status;
      if (right.nullValue(column)) {
        available = false;
        continue;
      }
      int compared = compare(value, outerDescriptor, expressions);
      if (compared < 0) {
        available = false;
        continue;
      }
      if (compared > 0) return StatusCode.OK;
      return captureRun(value, outerDescriptor, expressions);
    }
  }

  StatusCode next() {
    if (probeEmpty) return StatusCode.CONFLICT;
    StatusCode status;
    if (sorted) {
      if (runNext >= runEnd) return StatusCode.CONFLICT;
      status = store.readAt(runNext++, candidate);
    } else status = store.next(candidate);
    return status.isOk() ? writer.write(candidate, table) : status;
  }

  void emptyProbe() { probeEmpty = true; }
  long key() { return writer.key(candidate); }
  HeapRowResult row() { return writer.row(); }
  boolean hasResources() { return cursor.isActive() || store.hasResources(); }

  StatusCode close() {
    if (cursor.isActive()) {
      StatusCode status = session.closeScan(cursor);
      if (!status.isOk()) return status;
    }
    StatusCode status = cursor.reset();
    if (!status.isOk()) return status;
    status = store.close();
    if (!status.isOk()) return status;
    reader.reset();
    writer.reset();
    right.reset(0);
    candidate.reset(0);
    schema.reset();
    scan.reset();
    indexed.reset();
    table = null;
    column = -1;
    descriptor = 0;
    runValue = 0;
    position = 0;
    runStart = 0;
    runEnd = 0;
    runNext = 0;
    valueIndex = false;
    sorted = false;
    active = false;
    available = false;
    finished = false;
    runAvailable = false;
    probeEmpty = true;
    return StatusCode.OK;
  }

  private StatusCode captureRun(
      long value, int outerDescriptor, SqlExpressionEvaluator expressions) {
    StatusCode status = store.begin(schema, -1, false);
    while (status.isOk()) {
      if (!available) {
        status = ensure();
        if (status == StatusCode.CONFLICT) status = StatusCode.OK;
        if (!status.isOk() || !available) break;
      }
      if (right.nullValue(column)) {
        available = false;
        continue;
      }
      if (compare(value, outerDescriptor, expressions) != 0) break;
      status = store.append(right);
      available = false;
    }
    if (status.isOk()) status = store.finish();
    if (status.isOk()) publishRun(value);
    return status;
  }

  private StatusCode captureSorted(
      long value, int outerDescriptor, SqlExpressionEvaluator expressions) {
    while (position < store.rowCount()) {
      StatusCode status = store.readAt(position, right);
      if (!status.isOk()) return status;
      if (right.nullValue(column)) {
        position++;
        continue;
      }
      int compared = compare(value, outerDescriptor, expressions);
      if (compared < 0) {
        position++;
        continue;
      }
      if (compared > 0) return StatusCode.OK;
      runStart = position;
      do {
        position++;
        if (position >= store.rowCount()) break;
        status = store.readAt(position, right);
        if (!status.isOk()) return status;
      } while (!right.nullValue(column)
          && compare(value, outerDescriptor, expressions) == 0);
      runEnd = position;
      runNext = runStart;
      publishRun(value);
      return StatusCode.OK;
    }
    return StatusCode.OK;
  }

  private int compare(
      long value, int outerDescriptor, SqlExpressionEvaluator expressions) {
    return expressions.compareExact(
        right.value(column), descriptor, value, outerDescriptor);
  }

  private StatusCode ensure() {
    if (available) return StatusCode.OK;
    if (finished) return StatusCode.CONFLICT;
    StatusCode status = valueIndex
        ? session.nextValueScan(table, cursor, scan, indexed)
        : session.nextScan(cursor, scan);
    if (status == StatusCode.CONFLICT) {
      finished = true;
      return status;
    }
    if (!status.isOk()) return status;
    long key = valueIndex ? indexed.key() : scan.key();
    HeapRowResult source = valueIndex ? indexed.row() : scan.row();
    status = reader.read(key, source, table, right);
    if (status.isOk()) available = true;
    return status;
  }

  private StatusCode materialize() {
    StatusCode status = store.begin(schema, column, false);
    if (status.isOk()) status = session.beginScan(table, cursor);
    while (status.isOk()) {
      status = session.nextScan(cursor, scan);
      if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
        break;
      }
      if (!status.isOk()) break;
      status = reader.read(scan.key(), scan.row(), table, right);
      if (status.isOk()) status = store.append(right);
      scan.reset();
    }
    StatusCode runtime = status;
    StatusCode closed = cursor.isActive()
        ? session.closeScan(cursor) : StatusCode.OK;
    if (closed.isOk()) closed = cursor.reset();
    scan.reset();
    if (!runtime.isOk()) return runtime;
    return closed.isOk() ? store.finish() : closed;
  }

  private void publishRun(long value) {
    runValue = value;
    runAvailable = true;
    probeEmpty = false;
  }

  private void prepareSchema() {
    schema.set(table.columnCount());
    prepareRow(right, table);
    prepareRow(candidate, table);
    for (int current = 0; current < table.columnCount(); current++) {
      schema.setColumn(
          current,
          table.columnName(current),
          table.typeDescriptor(current),
          table.isNullable(current));
    }
  }

  private static void prepareRow(SqlBlockRow row, TableDefinition table) {
    row.reset(table.columnCount());
    for (int current = 0; current < table.columnCount(); current++) {
      if (table.isVarchar(current)) row.prepareText(current);
    }
  }
}
