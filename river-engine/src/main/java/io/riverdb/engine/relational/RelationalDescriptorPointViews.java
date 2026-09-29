package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;

/** Tracks point-result pins so transaction completion releases caller-owned views. */
final class RelationalDescriptorPointViews {
  private StoredTableRowView[] views = new StoredTableRowView[8];
  private int count;

  StatusCode add(StoredTableRowView view) {
    if (count == SqlShapeLimits.MAX_ACTIVE_QUERY_SCANS) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    if (count == views.length) {
      int length = Math.min(views.length * 2, SqlShapeLimits.MAX_ACTIVE_QUERY_SCANS);
      try {
        StoredTableRowView[] grown = new StoredTableRowView[length];
        System.arraycopy(views, 0, grown, 0, count);
        views = grown;
      } catch (OutOfMemoryError error) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
    }
    views[count++] = view;
    return StatusCode.OK;
  }

  void remove(StoredTableRowView view) {
    for (int index = 0; index < count; index++) {
      if (views[index] == view) {
        views[index] = views[--count];
        views[count] = null;
        return;
      }
    }
  }

  StatusCode closeAll() {
    while (count > 0) {
      StatusCode status = views[count - 1].releasePoint();
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }
}
