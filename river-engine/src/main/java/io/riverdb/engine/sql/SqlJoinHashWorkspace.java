package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalScanCursor;
import io.riverdb.engine.relational.RelationalScanResult;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.sql.SqlJoinChain;
import io.riverdb.storage.heap.HeapRowResult;

/** Lazy bounded right-build store and stable hash/fallback candidate source. */
final class SqlJoinHashWorkspace {
  private static final int HASH_ROWS = 1_024;
  private static final int BUCKETS = 2_048;
  private final RelationalSession session;
  private final SqlBlockRowStore store = new SqlBlockRowStore();
  private final SqlBlockSchema schema = new SqlBlockSchema();
  private final SqlBlockRow buildRow = new SqlBlockRow();
  private final SqlBlockRow candidateRow = new SqlBlockRow();
  private final SqlBlockRow outerRow = new SqlBlockRow();
  private final SqlBlockPhysicalRowReader reader = new SqlBlockPhysicalRowReader();
  private final SqlBlockPhysicalRowWriter writer = new SqlBlockPhysicalRowWriter();
  private final SqlJoinHashKey keys = new SqlJoinHashKey();
  private final RelationalScanCursor cursor = new RelationalScanCursor();
  private final RelationalScanResult result = new RelationalScanResult();
  private int[] heads;
  private int[] tails;
  private int[] next;
  private long[] hashes;
  private TableDefinition table;
  private int stage = -1;
  private int innerColumn = -1;
  private int candidate = -1;
  private boolean active;
  private boolean hashed;

  SqlJoinHashWorkspace(RelationalSession relationalSession) { session = relationalSession; }

  StatusCode begin(BoundSqlStatement bound) {
    StatusCode status = close();
    if (!status.isOk()) return status;
    stage = selectedStage(bound);
    if (stage < 0) return StatusCode.OK;
    table = bound.joinRole(stage + 1);
    innerColumn = bound.joinStrategyInnerColumn(stage);
    prepareSchema(table);
    prepareRow(outerRow, bound.joinRole(bound.joinStrategyOuterRole(stage)));
    writer.prepare();
    status = store.begin(schema, -1, false);
    if (!status.isOk()) return status;
    status = session.beginScan(table, cursor);
    if (!status.isOk()) return status;
    status = build();
    if (status.isOk()) status = store.finish();
    if (status.isOk() && !store.spilled()) status = indexBuild();
    active = status.isOk();
    return status;
  }

  StatusCode beginProbe(SqlJoinRoleRows rows, BoundSqlStatement bound) {
    if (!active) return StatusCode.INVALID_EXTERNAL_INPUT;
    candidate = -1;
    if (!hashed) {
      store.rewind();
      return StatusCode.OK;
    }
    int outerRole = bound.joinStrategyOuterRole(stage);
    int outerColumn = bound.joinStrategyOuterColumn(stage);
    HeapRowResult row = rows.row(outerRole);
    if (row == null) return StatusCode.OK;
    TableDefinition outerTable = bound.joinRole(outerRole);
    StatusCode status = reader.read(rows.key(outerRole), row, outerTable, outerRow);
    if (!status.isOk()) return status;
    if (outerRow.nullValue(outerColumn)) return StatusCode.OK;
    long hash = keys.decoded(
        outerRow, outerColumn, outerTable.typeDescriptor(outerColumn));
    candidate = heads[bucket(hash)];
    while (candidate >= 0 && hashes[candidate] != hash) candidate = next[candidate];
    return StatusCode.OK;
  }

  StatusCode nextCandidate() {
    int current;
    if (hashed) {
      current = candidate;
      if (current < 0) return StatusCode.CONFLICT;
      candidate = next[current];
      long hash = hashes[current];
      while (candidate >= 0 && hashes[candidate] != hash) candidate = next[candidate];
      StatusCode status = store.readAt(current, candidateRow);
      if (!status.isOk()) return status;
    } else {
      StatusCode status = store.next(candidateRow);
      if (!status.isOk()) return status;
    }
    return writer.write(candidateRow, table);
  }

  int stage() { return stage; }
  boolean hashed() { return active && hashed; }
  boolean fallback() { return active && !hashed; }
  long key() { return writer.key(candidateRow); }
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
    buildRow.reset(0);
    candidateRow.reset(0);
    outerRow.reset(0);
    clearIndex();
    schema.reset();
    result.reset();
    table = null;
    stage = -1;
    innerColumn = -1;
    candidate = -1;
    active = false;
    hashed = false;
    return StatusCode.OK;
  }

  private StatusCode build() {
    StatusCode status;
    while (true) {
      status = session.nextScan(cursor, result);
      if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
        break;
      }
      if (!status.isOk()) break;
      status = reader.read(result.key(), result.row(), table, buildRow);
      if (status.isOk()) status = store.append(buildRow);
      if (!status.isOk()) break;
      result.reset();
    }
    StatusCode runtime = status;
    StatusCode closed = session.closeScan(cursor);
    if (closed.isOk()) closed = cursor.reset();
    result.reset();
    return runtime.isOk() ? closed : runtime;
  }

  private StatusCode indexBuild() {
    ensureIndex();
    clearIndex();
    hashed = true;
    for (int row = 0; row < store.rowCount(); row++) {
      StatusCode status = store.readAt(row, candidateRow);
      if (!status.isOk()) return status;
      if (candidateRow.nullValue(innerColumn)) continue;
      long hash = keys.decoded(
          candidateRow, innerColumn, table.typeDescriptor(innerColumn));
      hashes[row] = hash;
      int bucket = bucket(hash);
      if (tails[bucket] < 0) heads[bucket] = row;
      else next[tails[bucket]] = row;
      tails[bucket] = row;
    }
    return StatusCode.OK;
  }

  private void prepareSchema(TableDefinition definition) {
    schema.set(definition.columnCount());
    prepareRow(buildRow, definition);
    prepareRow(candidateRow, definition);
    for (int column = 0; column < definition.columnCount(); column++) {
      schema.setColumn(
          column,
          definition.columnName(column),
          definition.typeDescriptor(column),
          definition.isNullable(column));
    }
  }

  private static void prepareRow(SqlBlockRow row, TableDefinition definition) {
    row.reset(definition.columnCount());
    for (int column = 0; column < definition.columnCount(); column++) {
      if (definition.isVarchar(column)) row.prepareText(column);
    }
  }

  private void ensureIndex() {
    if (heads != null) return;
    heads = new int[BUCKETS];
    tails = new int[BUCKETS];
    next = new int[HASH_ROWS];
    hashes = new long[HASH_ROWS];
  }

  private void clearIndex() {
    if (heads == null) return;
    for (int bucket = 0; bucket < BUCKETS; bucket++) {
      heads[bucket] = -1;
      tails[bucket] = -1;
    }
    for (int row = 0; row < HASH_ROWS; row++) {
      next[row] = -1;
      hashes[row] = 0;
    }
  }

  static int selectedStage(BoundSqlStatement bound) {
    SqlJoinChain chain = bound.command.joinChain();
    for (int stage = 0; stage < chain.stageCount(); stage++) {
      if (bound.joinStrategy(stage) == SqlJoinStrategy.HASH) return stage;
    }
    return -1;
  }

  private static int bucket(long hash) {
    return (int) (hash ^ hash >>> 32) & (BUCKETS - 1);
  }
}
