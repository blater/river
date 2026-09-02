package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalScanCursor;
import io.riverdb.engine.relational.RelationalScanResult;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.storage.heap.HeapRowResult;

/** Lazy bounded right-build store and stable hash/fallback candidate source. */
final class SqlJoinHashWorkspace {
  static final int HASH_ROWS = 1_024;
  static final int BUCKETS = 2_048;
  private final RelationalSession session;
  final SqlRetainedArrayAllocator allocator;
  final SqlBlockRowStore store;
  final SqlBlockSchema schema = new SqlBlockSchema();
  final SqlBlockRow buildRow;
  final SqlBlockRow candidateRow;
  final SqlBlockRow outerRow;
  final SqlBlockPhysicalRowReader reader;
  final SqlBlockPhysicalRowWriter writer;
  private final SqlJoinHashKey keys = new SqlJoinHashKey();
  private final RelationalScanCursor cursor = new RelationalScanCursor();
  private final RelationalScanResult result = new RelationalScanResult();
  int[] heads;
  int[] tails;
  int[] next;
  long[] hashes;
  TableDefinition table;
  int stage = -1;
  int innerColumn = -1;
  int outerDescriptor;
  private int candidate = -1;
  private long candidatePosition = -1;
  private long fallbackPosition;
  boolean active;
  private boolean hashed;

  SqlJoinHashWorkspace(
      RelationalSession relationalSession, SqlSessionShapeBudget budget) {
    this(relationalSession, SqlRetainedArrayAllocator.STANDARD, budget);
  }

  SqlJoinHashWorkspace(
      RelationalSession relationalSession, SqlRetainedArrayAllocator retainedAllocator,
      SqlSessionShapeBudget budget) {
    session = relationalSession;
    allocator = retainedAllocator;
    store = new SqlBlockRowStore(budget);
    buildRow = new SqlBlockRow(allocator);
    candidateRow = new SqlBlockRow(allocator);
    outerRow = new SqlBlockRow(allocator);
    reader = new SqlBlockPhysicalRowReader(allocator);
    writer = new SqlBlockPhysicalRowWriter(allocator);
  }

  StatusCode begin(
      io.riverdb.sql.SqlCommand command,
      SqlBoundJoinContext context) {
    return SqlJoinHashAdmission.begin(this, command, context);
  }

  StatusCode beginProbe(SqlJoinRoleRows rows, SqlBoundJoinContext context) {
    if (!active) return StatusCode.INVALID_EXTERNAL_INPUT;
    int outerRole = context.strategyOuterRole(stage);
    int outerColumn = context.strategyOuterColumn(stage);
    HeapRowResult row = rows.row(outerRole);
    if (row == null) return beginProbe((SqlBlockRow) null, outerColumn, 0);
    TableDefinition outerTable = context.table(outerRole);
    StatusCode status = reader.read(rows.key(outerRole), row, outerTable, outerRow);
    if (!status.isOk()) return status;
    return beginProbe(
        outerRow, outerColumn, outerTable.typeDescriptor(outerColumn));
  }

  StatusCode nextCandidate() {
    StatusCode status = nextDecodedCandidate();
    return status.isOk() ? writer.write(candidateRow, table) : status;
  }

  StatusCode beginProbe(SqlBlockRow row, int column, int descriptor) {
    if (!active) return StatusCode.INVALID_EXTERNAL_INPUT;
    candidate = -1;
    candidatePosition = -1;
    if (!hashed) {
      store.rewind();
      fallbackPosition = 0;
      return StatusCode.OK;
    }
    if (row == null || row.nullValue(column)) return StatusCode.OK;
    long hash = keys.decoded(
        row, column, descriptor, table.typeDescriptor(innerColumn));
    candidate = heads[bucket(hash)];
    while (candidate >= 0 && hashes[candidate] != hash) candidate = next[candidate];
    return StatusCode.OK;
  }

  StatusCode nextDecodedCandidate() {
    long current;
    if (hashed) {
      current = candidate;
      if (current < 0) return StatusCode.CONFLICT;
      int indexed = (int) current;
      candidate = next[indexed];
      long hash = hashes[indexed];
      while (candidate >= 0 && hashes[candidate] != hash) candidate = next[candidate];
      StatusCode status = store.readAt(current, candidateRow);
      if (!status.isOk()) return status;
    } else {
      current = fallbackPosition;
      StatusCode status = store.next(candidateRow);
      if (!status.isOk()) return status;
      fallbackPosition++;
    }
    candidatePosition = current;
    return StatusCode.OK;
  }

  int stage() { return stage; }
  long candidatePosition() { return candidatePosition; }
  SqlBlockRow decodedCandidate() { return candidateRow; }
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
    outerDescriptor = 0;
    candidate = -1;
    candidatePosition = -1;
    fallbackPosition = 0;
    active = false;
    hashed = false;
    return StatusCode.OK;
  }

  StatusCode openBuildScan() { return session.beginScan(table, cursor); }

  StatusCode build() {
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

  StatusCode indexBuild() {
    if (!withinHashCapacity()) return StatusCode.INVARIANT_BROKEN;
    clearIndex();
    hashed = true;
    int indexedRows = (int) store.rowCount();
    for (int row = 0; row < indexedRows; row++) {
      StatusCode status = store.readAt(row, candidateRow);
      if (!status.isOk()) return status;
      if (candidateRow.nullValue(innerColumn)) continue;
      long hash = keys.decoded(
          candidateRow, innerColumn, table.typeDescriptor(innerColumn), outerDescriptor);
      hashes[row] = hash;
      int bucket = bucket(hash);
      if (tails[bucket] < 0) heads[bucket] = row;
      else next[tails[bucket]] = row;
      tails[bucket] = row;
    }
    return StatusCode.OK;
  }

  StatusCode prepareDecodedBuild(
      TableDefinition inner, TableDefinition outer, int selectedStage,
      int selectedInnerColumn, int selectedOuterColumn) {
    StatusCode status = close();
    if (status.isOk()) status = SqlJoinHashAdmission.prepareRows(
        inner, outer, schema, reader, buildRow, candidateRow, outerRow, writer);
    if (status.isOk()) status = SqlJoinHashAdmission.prepareIndex(
        this, allocator, BUCKETS, HASH_ROWS);
    if (!status.isOk()) return status;
    stage = selectedStage;
    table = inner;
    innerColumn = selectedInnerColumn;
    outerDescriptor = outer.typeDescriptor(selectedOuterColumn);
    return store.begin(schema, -1, false);
  }

  StatusCode finishDecodedBuild() {
    StatusCode status = store.finish();
    if (status.isOk() && withinHashCapacity()) status = indexBuild();
    active = status.isOk();
    return status;
  }

  boolean withinHashCapacity() {
    return hashes != null && store.rowCount() <= hashes.length;
  }

  StatusCode failBegin(StatusCode failure) {
    StatusCode closed = close();
    return failure.isOk() ? closed : failure;
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

  private static int bucket(long hash) {
    return (int) (hash ^ hash >>> 32) & (BUCKETS - 1);
  }

}
