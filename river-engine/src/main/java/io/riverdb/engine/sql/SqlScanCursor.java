package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.TableSchema;

/** Caller-owned capability for one SQL scan generation. */
public final class SqlScanCursor {
  static final int MAXIMUM_PLAN_STEPS = 8;

  private final int[] projectedColumns = new int[TableSchema.MAXIMUM_COLUMNS];
  private SqlQueryExecution owner;
  private long generation;
  private long maximumRows = Long.MAX_VALUE;
  private long rowsReturned;
  private int projectedColumnCount;
  private boolean active;

  public StatusCode reset() {
    if (active) {
      return StatusCode.CONFLICT;
    }
    owner = null;
    generation = 0;
    maximumRows = Long.MAX_VALUE;
    rowsReturned = 0;
    projectedColumnCount = 0;
    return StatusCode.OK;
  }

  StatusCode claim(
      SqlQueryExecution execution,
      long scanGeneration,
      int[] projections,
      int projectionCount,
      long rowLimit) {
    if (active
        || execution == null
        || scanGeneration <= 0
        || projections == null
        || projectionCount <= 0
        || projectionCount > projectedColumns.length
        || rowLimit < 0) {
      return StatusCode.CONFLICT;
    }
    owner = execution;
    generation = scanGeneration;
    maximumRows = rowLimit;
    projectedColumnCount = projectionCount;
    for (int index = 0; index < projectionCount; index++) {
      projectedColumns[index] = projections[index];
    }
    rowsReturned = 0;
    active = true;
    return StatusCode.OK;
  }

  boolean isOwnedBy(SqlQueryExecution execution, long scanGeneration) {
    return active && owner == execution && generation == scanGeneration;
  }

  boolean limitReached() {
    return rowsReturned >= maximumRows;
  }

  void complete() {
    active = false;
  }

  void rowReturned() {
    rowsReturned++;
  }

  public int projectedColumn(int index) {
    return index >= 0 && index < projectedColumnCount ? projectedColumns[index] : -1;
  }

  public int projectedColumnCount() {
    return projectedColumnCount;
  }

  public long rowsReturned() {
    return rowsReturned;
  }

  public boolean isActive() {
    return active;
  }
}
