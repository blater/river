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
  private final SqlBlockRowStore store;
  private final SqlBlockSchema schema = new SqlBlockSchema();
  private final SqlBlockRow right;
  private final SqlBlockRow candidate;
  private final SqlBlockPhysicalRowReader reader;
  private final SqlBlockPhysicalRowWriter writer;
  private final SqlBlockRowValueComparator comparator = new SqlBlockRowValueComparator();
  private final SqlJoinMergeKey runKey;
  private final RelationalScanCursor cursor = new RelationalScanCursor();
  private final RelationalScanResult scan = new RelationalScanResult();
  private final ValueIndexLookupResult indexed = new ValueIndexLookupResult();
  private TableDefinition table;
  private int column = -1;
  private int descriptor;
  private long position;
  private long runStart;
  private long runEnd;
  private long runNext;
  private boolean valueIndex;
  private boolean sorted;
  private boolean active;
  private boolean available;
  private boolean finished;
  private boolean probeEmpty;

  SqlJoinMergeRightRows(
      RelationalSession relationalSession, SqlSessionShapeBudget budget) {
    this(relationalSession, SqlRetainedArrayAllocator.STANDARD, budget);
  }

  SqlJoinMergeRightRows(
      RelationalSession relationalSession, SqlRetainedArrayAllocator retainedAllocator,
      SqlSessionShapeBudget budget) {
    session = relationalSession;
    store = new SqlBlockRowStore(budget);
    right = new SqlBlockRow(retainedAllocator);
    candidate = new SqlBlockRow(retainedAllocator);
    reader = new SqlBlockPhysicalRowReader(retainedAllocator);
    writer = new SqlBlockPhysicalRowWriter(retainedAllocator);
    runKey = new SqlJoinMergeKey(budget);
  }

  StatusCode begin(TableDefinition definition, int keyColumn, int outerDescriptor) {
    StatusCode status = close();
    if (!status.isOk()) return status;
    boolean needsSort = keyColumn > 0 && !definition.hasIndexOn(keyColumn);
    boolean usesIndex = !needsSort && keyColumn > 0;
    status = SqlJoinMergeAdmission.prepare(
        definition, schema, reader, right, candidate, writer);
    if (!status.isOk()) return status;
    table = definition;
    column = keyColumn;
    descriptor = table.typeDescriptor(column);
    status = runKey.prepare(outerDescriptor);
    if (!status.isOk()) return failBegin(status);
    sorted = needsSort;
    valueIndex = usesIndex;
    status = sorted ? materialize() : valueIndex
        ? session.beginValueScan(table, column, cursor)
        : session.beginScan(table, cursor);
    active = status.isOk();
    return status.isOk() ? status : failBegin(status);
  }

  StatusCode beginProbe(
      SqlBlockRow probe, int probeColumn, int outerDescriptor) {
    if (!active) return StatusCode.INVALID_EXTERNAL_INPUT;
    probeEmpty = true;
    if (runKey.available()
        && runKey.compare(probe, probeColumn, outerDescriptor, comparator) == 0) {
      if (sorted) runNext = runStart; else store.rewind();
      probeEmpty = false;
      return StatusCode.OK;
    }
    runKey.invalidate();
    if (sorted) return captureSorted(probe, probeColumn, outerDescriptor);
    while (true) {
      StatusCode status = ensure();
      if (status == StatusCode.CONFLICT) return StatusCode.OK;
      if (!status.isOk()) return status;
      if (right.nullValue(column)) {
        available = false;
        continue;
      }
      int compared = compare(probe, probeColumn, outerDescriptor);
      if (compared < 0) {
        available = false;
        continue;
      }
      if (compared > 0) return StatusCode.OK;
      return captureRun(probe, probeColumn, outerDescriptor);
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
    runKey.reset();
    position = 0;
    runStart = 0;
    runEnd = 0;
    runNext = 0;
    valueIndex = false;
    sorted = false;
    active = false;
    available = false;
    finished = false;
    probeEmpty = true;
    return StatusCode.OK;
  }

  private StatusCode captureRun(
      SqlBlockRow probe, int probeColumn, int outerDescriptor) {
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
      if (compare(probe, probeColumn, outerDescriptor) != 0) break;
      status = store.append(right);
      available = false;
    }
    if (status.isOk()) status = store.finish();
    if (status.isOk()) status = publishRun(probe, probeColumn);
    return status;
  }

  private StatusCode captureSorted(
      SqlBlockRow probe, int probeColumn, int outerDescriptor) {
    while (position < store.rowCount()) {
      StatusCode status = store.readAt(position, right);
      if (!status.isOk()) return status;
      if (right.nullValue(column)) {
        position++;
        continue;
      }
      int compared = compare(probe, probeColumn, outerDescriptor);
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
          && compare(probe, probeColumn, outerDescriptor) == 0);
      runEnd = position;
      runNext = runStart;
      return publishRun(probe, probeColumn);
    }
    return StatusCode.OK;
  }

  private int compare(
      SqlBlockRow probe, int probeColumn, int outerDescriptor) {
    return comparator.compare(
        right, column, descriptor, probe, probeColumn, outerDescriptor);
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

  private StatusCode publishRun(SqlBlockRow probe, int probeColumn) {
    StatusCode status = runKey.capture(probe, probeColumn);
    probeEmpty = !status.isOk();
    return status;
  }

  private StatusCode failBegin(StatusCode failure) {
    StatusCode closed = close();
    return failure.isOk() ? closed : failure;
  }
}
