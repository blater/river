package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalScanCursor;
import io.riverdb.engine.relational.RelationalScanResult;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.engine.relational.ValueIndexLookupResult;
import io.riverdb.sql.SqlQuery;
import io.riverdb.storage.heap.HeapRowResult;

/** Owns reusable active rows and physical cursors by nested graph depth. */
final class SqlSubqueryFrames implements SqlNestedRowProvider {
  private final RelationalSession session;
  private final BoundSqlQuery query;
  private final SqlSubqueryAccess access;
  private final RelationalScanCursor[] cursors =
      new RelationalScanCursor[SqlQuery.MAXIMUM_QUERY_BLOCKS];
  private final RelationalScanResult[] results =
      new RelationalScanResult[SqlQuery.MAXIMUM_QUERY_BLOCKS];
  private final ValueIndexLookupResult[] indexed =
      new ValueIndexLookupResult[SqlQuery.MAXIMUM_QUERY_BLOCKS];
  private final SqlJoinOuterRow[] owned =
      new SqlJoinOuterRow[SqlQuery.MAXIMUM_QUERY_BLOCKS];
  private final SqlPredicateOperand[] projected =
      new SqlPredicateOperand[SqlQuery.MAXIMUM_QUERY_BLOCKS];
  private final long[] keys = new long[SqlQuery.MAXIMUM_QUERY_BLOCKS];
  private final HeapRowResult[] rows = new HeapRowResult[SqlQuery.MAXIMUM_QUERY_BLOCKS];
  private final TableDefinition[] tables =
      new TableDefinition[SqlQuery.MAXIMUM_QUERY_BLOCKS];
  private final boolean[] copied = new boolean[SqlQuery.MAXIMUM_QUERY_BLOCKS];

  SqlSubqueryFrames(
      RelationalSession relationalSession,
      BoundSqlStatement bound,
      SqlExpressionEvaluator evaluator,
      SqlTemporalContext temporal) {
    session = relationalSession;
    query = bound.executableQuery;
    access = new SqlSubqueryAccess(relationalSession, bound, evaluator, temporal);
  }

  void prepareAccess() { access.prepare(); }
  SqlSubqueryAccess access() { return access; }

  void prepare(int block, boolean textProjection) {
    int frame = frame(block);
    if (cursors[frame] == null) {
      cursors[frame] = new RelationalScanCursor();
      results[frame] = new RelationalScanResult();
      indexed[frame] = new ValueIndexLookupResult();
      owned[frame] = new SqlJoinOuterRow();
      projected[frame] = new SqlPredicateOperand();
    }
    if (parent(block)) owned[frame].prepare();
    if (textProjection) projected[frame].prepareText();
    tables[block] = query.block(block).table();
  }

  void activate(int block, long key, HeapRowResult row) {
    if (copied[block]) release(block);
    keys[block] = key;
    rows[block] = row;
    tables[block] = query.block(block).table();
  }

  StatusCode own(int block) {
    if (copied[block]) return StatusCode.OK;
    SqlJoinOuterRow target = owned[frame(block)];
    StatusCode status = target.capture(rows[block]);
    if (status.isOk()) {
      rows[block] = target.row();
      copied[block] = true;
    }
    return status;
  }

  StatusCode begin(int child) {
    tables[child] = query.block(child).table();
    return access.begin(child, this, cursors[frame(child)]);
  }

  StatusCode next(int child) {
    int frame = frame(child);
    StatusCode status = access.next(
        child, cursors[frame], results[frame], indexed[frame]);
    if (status.isOk()) activate(
        child,
        access.valueIndex(child) ? indexed[frame].key() : results[frame].key(),
        access.valueIndex(child) ? indexed[frame].row() : results[frame].row());
    return status;
  }

  SqlPredicateOperand projected(int block) { return projected[frame(block)]; }

  StatusCode finish(int child, StatusCode body) {
    int frame = frame(child);
    StatusCode closed = closeFrame(frame);
    if (closed.isOk()) release(child);
    return body.isOk() ? closed : body;
  }

  HeapRowResult evaluatedRow(int block, HeapRowResult original) {
    return copied[block] ? rows[block] : original;
  }

  void release(int block) {
    if (copied[block]) {
      owned[frame(block)].reset();
      copied[block] = false;
    }
    keys[block] = 0;
    rows[block] = null;
  }

  @Override public long key(int block) { return valid(block) ? keys[block] : 0; }
  @Override public HeapRowResult row(int block) { return valid(block) ? rows[block] : null; }
  @Override public TableDefinition table(int block) {
    return valid(block) ? tables[block] : null;
  }

  StatusCode close() {
    for (int frame = cursors.length - 1; frame >= 0; frame--) {
      StatusCode status = closeFrame(frame);
      if (!status.isOk()) return status;
    }
    for (int block = 0; block < rows.length; block++) release(block);
    for (int frame = 0; frame < projected.length; frame++) {
      if (projected[frame] != null) projected[frame].clear();
    }
    return StatusCode.OK;
  }

  boolean hasResources() {
    for (int frame = 0; frame < cursors.length; frame++) {
      if (cursors[frame] != null && cursors[frame].isActive()) return true;
    }
    return false;
  }

  private StatusCode closeFrame(int frame) {
    if (cursors[frame] == null || !cursors[frame].isActive()) return StatusCode.OK;
    StatusCode status = session.closeScan(cursors[frame]);
    if (status.isOk()) {
      status = cursors[frame].reset();
      results[frame].reset();
      indexed[frame].reset();
    }
    return status;
  }

  private int frame(int block) { return query.blockDepth(block) - 1; }
  private boolean parent(int block) {
    for (int edge = 0; edge < query.edgeCount(); edge++) {
      if (query.edgeParent(edge) == block) return true;
    }
    return false;
  }
  private static boolean valid(int block) {
    return block >= 0 && block < SqlQuery.MAXIMUM_QUERY_BLOCKS;
  }
}
