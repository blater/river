package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.TableSchema;

/** Exact retained-byte charge for active-run canonical merge heads. */
final class SqlSortSpillHeadBudget {
  private final SqlSessionShapeBudget budget;
  private long retained;

  SqlSortSpillHeadBudget(SqlSessionShapeBudget shapeBudget) { budget = shapeBudget; }

  StatusCode reserve(int slots, int projections, boolean textRows) {
    long words = (projections + Long.SIZE - 1L) >>> 6;
    long bytes = (long) slots * (4L * Long.BYTES + Integer.BYTES + 1
        + 2L * projections * Long.BYTES + words * Long.BYTES);
    if (textRows) bytes += (long) slots * TableSchema.MAXIMUM_ROW_BYTES;
    if (bytes <= retained || budget == null) return StatusCode.OK;
    StatusCode status = budget.reserve(bytes - retained);
    if (status.isOk()) retained = bytes;
    return status;
  }
}
