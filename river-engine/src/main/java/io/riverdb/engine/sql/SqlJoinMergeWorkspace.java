package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalScanCursor;
import io.riverdb.engine.relational.RelationalScanResult;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.engine.relational.ValueIndexLookupResult;
import io.riverdb.storage.heap.HeapRowResult;

/** Ordered right stream plus one spillable duplicate run for stage-zero merge. */
final class SqlJoinMergeWorkspace {
  private final RelationalSession session;
  private final SqlBlockRowStore run = new SqlBlockRowStore();
  private final SqlBlockSchema schema = new SqlBlockSchema();
  private final SqlBlockRow outer = new SqlBlockRow();
  private final SqlBlockRow right = new SqlBlockRow();
  private final SqlBlockRow candidate = new SqlBlockRow();
  private final SqlBlockPhysicalRowReader reader = new SqlBlockPhysicalRowReader();
  private final SqlBlockPhysicalRowWriter writer = new SqlBlockPhysicalRowWriter();
  private final RelationalScanCursor cursor = new RelationalScanCursor();
  private final RelationalScanResult scan = new RelationalScanResult();
  private final ValueIndexLookupResult indexed = new ValueIndexLookupResult();
  private TableDefinition outerTable;
  private TableDefinition rightTable;
  private int outerColumn = -1;
  private int innerColumn = -1;
  private int outerDescriptor;
  private int innerDescriptor;
  private long lastOuterValue;
  private long runOuterValue;
  private boolean rightIndexed;
  private boolean active;
  private boolean rightAvailable;
  private boolean rightFinished;
  private boolean lastOuterAvailable;
  private boolean runAvailable;
  private boolean probeEmpty;

  SqlJoinMergeWorkspace(RelationalSession relationalSession) {
    session = relationalSession;
  }

  StatusCode begin(BoundSqlStatement bound) {
    StatusCode status = close();
    if (!status.isOk()) return status;
    if (bound.joinStrategy(0) != SqlJoinStrategy.MERGE) return StatusCode.OK;
    outerTable = bound.joinRole(0);
    rightTable = bound.joinRole(1);
    outerColumn = bound.joinStrategyOuterColumn(0);
    innerColumn = bound.joinStrategyInnerColumn(0);
    outerDescriptor = outerTable.typeDescriptor(outerColumn);
    innerDescriptor = rightTable.typeDescriptor(innerColumn);
    rightIndexed = innerColumn > 0;
    prepareSchema();
    prepareRow(outer, outerTable);
    writer.prepare();
    status = rightIndexed
        ? session.beginValueScan(rightTable, innerColumn, cursor)
        : session.beginScan(rightTable, cursor);
    active = status.isOk();
    return status;
  }

  StatusCode beginProbe(
      SqlJoinRoleRows rows,
      SqlExpressionEvaluator expressions) {
    if (!active) return StatusCode.INVALID_EXTERNAL_INPUT;
    probeEmpty = true;
    HeapRowResult source = rows.row(0);
    if (source == null) return StatusCode.CORRUPTION;
    StatusCode status = reader.read(rows.key(0), source, outerTable, outer);
    if (!status.isOk()) return status;
    if (outer.nullValue(outerColumn)) {
      lastOuterAvailable = false;
      return StatusCode.OK;
    }
    long value = outer.value(outerColumn);
    if (lastOuterAvailable
        && expressions.compareExact(
            value, outerDescriptor, lastOuterValue, outerDescriptor) < 0) {
      return StatusCode.INVARIANT_BROKEN;
    }
    lastOuterValue = value;
    lastOuterAvailable = true;
    if (runAvailable && expressions.compareExact(
        value, outerDescriptor, runOuterValue, outerDescriptor) == 0) {
      run.rewind();
      probeEmpty = false;
      return StatusCode.OK;
    }
    runAvailable = false;
    while (true) {
      status = ensureRight();
      if (status == StatusCode.CONFLICT) return StatusCode.OK;
      if (!status.isOk()) return status;
      if (right.nullValue(innerColumn)) {
        rightAvailable = false;
        continue;
      }
      int compared = expressions.compareExact(
          right.value(innerColumn), innerDescriptor, value, outerDescriptor);
      if (compared < 0) {
        rightAvailable = false;
        continue;
      }
      if (compared > 0) return StatusCode.OK;
      return captureRun(value, expressions);
    }
  }

  StatusCode nextCandidate() {
    if (probeEmpty) return StatusCode.CONFLICT;
    StatusCode status = run.next(candidate);
    return status.isOk() ? writer.write(candidate, rightTable) : status;
  }

  long key() { return writer.key(candidate); }
  HeapRowResult row() { return writer.row(); }
  boolean hasResources() { return cursor.isActive() || run.hasResources(); }

  StatusCode close() {
    if (cursor.isActive()) {
      StatusCode status = session.closeScan(cursor);
      if (!status.isOk()) return status;
    }
    StatusCode status = cursor.reset();
    if (!status.isOk()) return status;
    status = run.close();
    if (!status.isOk()) return status;
    reader.reset();
    writer.reset();
    outer.reset(0);
    right.reset(0);
    candidate.reset(0);
    schema.reset();
    scan.reset();
    indexed.reset();
    outerTable = null;
    rightTable = null;
    outerColumn = -1;
    innerColumn = -1;
    outerDescriptor = 0;
    innerDescriptor = 0;
    lastOuterValue = 0;
    runOuterValue = 0;
    rightIndexed = false;
    active = false;
    rightAvailable = false;
    rightFinished = false;
    lastOuterAvailable = false;
    runAvailable = false;
    probeEmpty = true;
    return StatusCode.OK;
  }

  private StatusCode captureRun(
      long outerValue,
      SqlExpressionEvaluator expressions) {
    StatusCode status = run.begin(schema, -1, false);
    while (status.isOk()) {
      if (!rightAvailable) {
        status = ensureRight();
        if (status == StatusCode.CONFLICT) status = StatusCode.OK;
        if (!status.isOk() || !rightAvailable) break;
      }
      if (right.nullValue(innerColumn)) {
        rightAvailable = false;
        continue;
      }
      int compared = expressions.compareExact(
          right.value(innerColumn), innerDescriptor, outerValue, outerDescriptor);
      if (compared != 0) break;
      status = run.append(right);
      rightAvailable = false;
    }
    if (status.isOk()) status = run.finish();
    if (status.isOk()) {
      runOuterValue = outerValue;
      runAvailable = true;
      probeEmpty = false;
    }
    return status;
  }

  private StatusCode ensureRight() {
    if (rightAvailable) return StatusCode.OK;
    if (rightFinished) return StatusCode.CONFLICT;
    StatusCode status = rightIndexed
        ? session.nextValueScan(rightTable, cursor, scan, indexed)
        : session.nextScan(cursor, scan);
    if (status == StatusCode.CONFLICT) {
      rightFinished = true;
      return status;
    }
    if (!status.isOk()) return status;
    long key = rightIndexed ? indexed.key() : scan.key();
    HeapRowResult row = rightIndexed ? indexed.row() : scan.row();
    status = reader.read(key, row, rightTable, right);
    if (status.isOk()) rightAvailable = true;
    return status;
  }

  private void prepareSchema() {
    schema.set(rightTable.columnCount());
    prepareRow(right, rightTable);
    prepareRow(candidate, rightTable);
    for (int column = 0; column < rightTable.columnCount(); column++) {
      schema.setColumn(
          column,
          rightTable.columnName(column),
          rightTable.typeDescriptor(column),
          rightTable.isNullable(column));
    }
  }

  private static void prepareRow(SqlBlockRow row, TableDefinition table) {
    row.reset(table.columnCount());
    for (int column = 0; column < table.columnCount(); column++) {
      if (table.isVarchar(column)) row.prepareText(column);
    }
  }
}
