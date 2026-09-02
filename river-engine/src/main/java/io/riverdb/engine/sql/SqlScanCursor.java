package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;

/** Caller-owned capability for one SQL scan generation. */
public final class SqlScanCursor {
  static final int MAXIMUM_PLAN_STEPS = SqlShapeLimits.MAX_PLAN_STEPS;
  private static final int[] PLAN_PROJECTION = {0};

  private final SqlScanProjectionColumns projectedColumns = new SqlScanProjectionColumns();
  private SqlQueryExecution owner;
  private long generation;
  private long maximumRows = Long.MAX_VALUE;
  private long rowsReturned;
  private boolean active;

  public StatusCode reset() {
    if (active) {
      return StatusCode.CONFLICT;
    }
    owner = null;
    generation = 0;
    maximumRows = Long.MAX_VALUE;
    rowsReturned = 0;
    projectedColumns.reset();
    return StatusCode.OK;
  }

  StatusCode claim(
      SqlQueryExecution execution,
      long scanGeneration,
      int[] projections,
      int projectionCount,
      long rowLimit) {
    StatusCode reserved = SqlScanProjectionColumns.validateClaim(
        active, execution, scanGeneration, projections, projectionCount, rowLimit);
    if (reserved.isOk()) reserved = projectedColumns.set(projections, projectionCount);
    if (!reserved.isOk()) return reserved;
    owner = execution;
    generation = scanGeneration;
    maximumRows = rowLimit;
    rowsReturned = 0;
    active = true;
    return StatusCode.OK;
  }

  StatusCode claimPlan(SqlQueryExecution execution, long scanGeneration) {
    return claim(execution, scanGeneration, PLAN_PROJECTION, 1, Long.MAX_VALUE);
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
    return projectedColumns.get(index);
  }

  public int projectedColumnCount() {
    return projectedColumns.count();
  }

  public long rowsReturned() {
    return rowsReturned;
  }

  public boolean isActive() {
    return active;
  }
}
