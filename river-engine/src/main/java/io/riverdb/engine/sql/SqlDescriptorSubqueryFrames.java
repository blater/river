package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.sql.SqlQuery;

/** Actual-block descriptor frames kept separate from depth-shared legacy cursors. */
final class SqlDescriptorSubqueryFrames {
  private final RelationalSession session;
  private final BoundSqlQuery query;
  private final SqlDescriptorSubqueryRowFrame[] frames =
      new SqlDescriptorSubqueryRowFrame[SqlQuery.MAXIMUM_QUERY_BLOCKS];

  SqlDescriptorSubqueryFrames(
      RelationalSession relationalSession, BoundSqlQuery boundQuery) {
    session = relationalSession;
    query = boundQuery;
  }

  StatusCode prepare(int block) {
    if (!query.block(block).descriptorRole(0)) return StatusCode.OK;
    if (frames[block] == null) {
      try {
        frames[block] = new SqlDescriptorSubqueryRowFrame(session);
      } catch (OutOfMemoryError error) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
    }
    return frames[block].prepare(query.block(block).tableName());
  }

  StatusCode begin(int block) { return frames[block].begin(); }
  StatusCode next(int block) { return frames[block].next(); }
  StatusCode closeScan(int block) { return frames[block].closeScan(); }
  long key(int block) { return frames[block].key(); }
  SqlBlockRow row(int block) {
    return contains(block) && frames[block].available() ? frames[block].row() : null;
  }

  boolean contains(int block) {
    return block >= 0 && block < frames.length && query.block(block) != null
        && query.block(block).descriptorRole(0) && frames[block] != null;
  }

  boolean deeperResources(int frame) {
    for (int block = 0; block < frames.length; block++) {
      if (frames[block] != null && frames[block].active()
          && query.blockDepth(block) - 1 > frame) return true;
    }
    return false;
  }

  boolean hasResources() {
    for (SqlDescriptorSubqueryRowFrame frame : frames) {
      if (frame != null && frame.active()) return true;
    }
    return false;
  }

  StatusCode reset() {
    for (int block = frames.length - 1; block >= 0; block--) {
      if (frames[block] == null) continue;
      StatusCode status = frames[block].reset();
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }
}
