package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalScanCursor;
import io.riverdb.engine.relational.RelationalScanResult;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.engine.relational.ValueIndexLookupResult;
import io.riverdb.sql.SqlQuery;
import io.riverdb.storage.heap.HeapRowResult;

/** Depth-shared legacy and actual-block descriptor frames for nested table sources. */
final class SqlSubqueryTableFrames {
  private final RelationalSession session;
  private final BoundSqlQuery query;
  private final SqlSubqueryAccess access;
  private final SqlDescriptorSubqueryFrames descriptors;
  private final RelationalScanCursor[] cursors = new RelationalScanCursor[SqlQuery.MAXIMUM_QUERY_BLOCKS];
  private final RelationalScanResult[] results = new RelationalScanResult[SqlQuery.MAXIMUM_QUERY_BLOCKS];
  private final ValueIndexLookupResult[] indexed = new ValueIndexLookupResult[SqlQuery.MAXIMUM_QUERY_BLOCKS];
  private final SqlJoinOuterRow[] owned = new SqlJoinOuterRow[SqlQuery.MAXIMUM_QUERY_BLOCKS];
  private final SqlPredicateOperand[] projected = new SqlPredicateOperand[SqlQuery.MAXIMUM_QUERY_BLOCKS];
  private final long[] keys = new long[SqlQuery.MAXIMUM_QUERY_BLOCKS];
  private final HeapRowResult[] rows = new HeapRowResult[SqlQuery.MAXIMUM_QUERY_BLOCKS];
  private final TableDefinition[] definitions = new TableDefinition[SqlQuery.MAXIMUM_QUERY_BLOCKS];
  private final boolean[] copied = new boolean[SqlQuery.MAXIMUM_QUERY_BLOCKS];

  SqlSubqueryTableFrames(RelationalSession relationalSession, BoundSqlStatement bound,
      SqlExpressionEvaluator evaluator, SqlTemporalContext temporal) {
    session = relationalSession;
    query = bound.executableQuery;
    access = new SqlSubqueryAccess(relationalSession, bound, evaluator, temporal);
    descriptors = new SqlDescriptorSubqueryFrames(relationalSession, bound);
  }

  void prepareAccess() { access.prepare(); }

  StatusCode prepare(int block, boolean valueProjection, boolean textProjection,
      boolean tableSource, boolean parent) {
    int frame = frame(block);
    if (valueProjection && projected[frame] == null) projected[frame] = new SqlPredicateOperand();
    if (!tableSource) return prepareProjection(frame, textProjection);
    StatusCode status = query.block(block).descriptorRole(0)
        ? descriptors.prepare(block) : prepareLegacy(frame, parent);
    if (status.isOk()) status = prepareProjection(frame, textProjection);
    if (status.isOk()) definitions[block] = query.block(block).table();
    return status;
  }

  private StatusCode prepareLegacy(int frame, boolean parent) {
    if (cursors[frame] == null) {
      try {
        cursors[frame] = new RelationalScanCursor();
        results[frame] = new RelationalScanResult();
        indexed[frame] = new ValueIndexLookupResult();
        owned[frame] = new SqlJoinOuterRow();
      } catch (OutOfMemoryError error) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
    }
    if (parent) owned[frame].prepare();
    return StatusCode.OK;
  }

  private StatusCode prepareProjection(int frame, boolean textProjection) {
    if (textProjection) projected[frame].prepareText();
    return StatusCode.OK;
  }

  void activate(int block, long key, HeapRowResult row) {
    if (copied[block]) release(block);
    keys[block] = key;
    rows[block] = row;
    definitions[block] = query.block(block).table();
  }

  StatusCode own(int block) {
    if (descriptor(block) || copied[block]) return StatusCode.OK;
    SqlJoinOuterRow target = owned[frame(block)];
    StatusCode status = target.capture(rows[block]);
    if (status.isOk()) {
      rows[block] = target.row();
      copied[block] = true;
    }
    return status;
  }

  StatusCode begin(int block, SqlNestedRowProvider provider) {
    definitions[block] = query.block(block).table();
    return descriptor(block) ? descriptors.begin(block, provider)
        : access.begin(block, provider, cursors[frame(block)]);
  }

  StatusCode next(int block) {
    if (descriptor(block)) {
      StatusCode status = descriptors.next(block);
      if (status.isOk()) keys[block] = descriptors.key(block);
      return status;
    }
    int frame = frame(block);
    StatusCode status = access.next(block, cursors[frame], results[frame], indexed[frame]);
    if (status.isOk()) activate(block,
        access.valueIndex(block) ? indexed[frame].key() : results[frame].key(),
        access.valueIndex(block) ? indexed[frame].row() : results[frame].row());
    return status;
  }

  StatusCode closeBlock(int block) {
    return descriptor(block) ? descriptors.closeScan(block) : closeFrame(frame(block));
  }

  void release(int block) {
    if (descriptor(block)) {
      keys[block] = 0;
      return;
    }
    if (copied[block]) {
      owned[frame(block)].reset();
      copied[block] = false;
    }
    keys[block] = 0;
    rows[block] = null;
  }

  long key(int block, int role) { return valid(block) && role == 0 ? keys[block] : 0; }
  HeapRowResult row(int block, int role) { return valid(block) && role == 0 ? rows[block] : null; }
  TableDefinition table(int block, int role) {
    BoundSqlQuery.Block source = valid(block) ? query.block(block) : null;
    return source == null ? null : source.table(role);
  }
  SqlBlockRow blockRow(int block, int role) { return role == 0 ? descriptors.row(block) : null; }
  int accessColumn(int block) {
    return descriptor(block) ? descriptors.accessColumn(block) : access.column(block);
  }
  SqlPredicateOperand projected(int block) { return projected[frame(block)]; }
  HeapRowResult evaluatedRow(int block, HeapRowResult original) {
    return copied[block] ? rows[block] : original;
  }

  StatusCode close() {
    StatusCode status = descriptors.reset();
    for (int frame = cursors.length - 1; status.isOk() && frame >= 0; frame--) status = closeFrame(frame);
    if (!status.isOk()) return status;
    for (int block = 0; block < rows.length; block++) release(block);
    for (SqlPredicateOperand operand : projected) if (operand != null) operand.clear();
    return StatusCode.OK;
  }

  boolean hasResources() {
    if (descriptors.hasResources()) return true;
    for (RelationalScanCursor cursor : cursors) if (cursor != null && cursor.isActive()) return true;
    return false;
  }

  boolean deeperResources(int frame) {
    if (descriptors.deeperResources(frame)) return true;
    for (int deeper = frame + 1; deeper < cursors.length; deeper++) {
      if (cursors[deeper] != null && cursors[deeper].isActive()) return true;
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

  private boolean descriptor(int block) { return descriptors.contains(block); }
  private int frame(int block) { return query.blockDepth(block) - 1; }
  private static boolean valid(int block) { return block >= 0 && block < SqlQuery.MAXIMUM_QUERY_BLOCKS; }
}
