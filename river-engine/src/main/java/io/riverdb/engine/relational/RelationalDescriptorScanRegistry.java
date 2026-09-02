package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;

/** Bounded session ownership for concurrently open descriptor scan cursors. */
final class RelationalDescriptorScanRegistry {
  private final RelationalDescriptorScanCursor[] cursors =
      new RelationalDescriptorScanCursor[SqlShapeLimits.MAX_ACTIVE_QUERY_SCANS];
  private int count;

  StatusCode admit(RelationalDescriptorScanCursor cursor) {
    if (cursor == null || find(cursor) >= 0) return StatusCode.CONFLICT;
    if (count == cursors.length) return StatusCode.RESOURCE_EXHAUSTED;
    cursors[count++] = cursor;
    return StatusCode.OK;
  }

  StatusCode release(RelationalDescriptorScanCursor cursor) {
    int found = find(cursor);
    if (found < 0) return StatusCode.CONFLICT;
    cursors[found] = cursors[--count];
    cursors[count] = null;
    return StatusCode.OK;
  }

  int count() { return count; }
  RelationalDescriptorScanCursor last() { return cursors[count - 1]; }

  private int find(RelationalDescriptorScanCursor cursor) {
    for (int index = 0; index < count; index++) {
      if (cursors[index] == cursor) return index;
    }
    return -1;
  }
}
