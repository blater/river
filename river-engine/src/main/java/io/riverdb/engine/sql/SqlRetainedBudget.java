package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;

/** Database-accounted ownership boundary for memory retained by a SQL session. */
public interface SqlRetainedBudget {
  StatusCode reserveRetainedBytes(long bytes);

  StatusCode releaseRetainedBytes(long bytes);
}
