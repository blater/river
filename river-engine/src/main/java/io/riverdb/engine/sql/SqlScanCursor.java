package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalScanCursor;
import io.riverdb.engine.relational.TableSchema;

/** Caller-owned capability for one ordered SQL table scan. */
public final class SqlScanCursor {
  private final RelationalScanCursor relational = new RelationalScanCursor();
  private SqlSession owner;
  private boolean implicitTransaction;
  private boolean valueIndex;
  private final int[] projectedColumns = new int[TableSchema.MAXIMUM_COLUMNS];
  private int projectedColumnCount;
  private boolean active;
  private long rowsReturned;

  public StatusCode reset() {
    if (active) {
      return StatusCode.CONFLICT;
    }
    owner = null;
    implicitTransaction = false;
    valueIndex = false;
    projectedColumnCount = 0;
    rowsReturned = 0;
    return relational.reset();
  }

  RelationalScanCursor relational() {
    return relational;
  }

  StatusCode claim(
      SqlSession session,
      boolean implicit,
      boolean indexedValue,
      int[] projections,
      int projectionCount) {
    if (active
        || projections == null
        || projectionCount <= 0
        || projectionCount > projectedColumns.length) {
      return StatusCode.CONFLICT;
    }
    owner = session;
    implicitTransaction = implicit;
    valueIndex = indexedValue;
    projectedColumnCount = projectionCount;
    for (int index = 0; index < projectionCount; index++) {
      projectedColumns[index] = projections[index];
    }
    active = true;
    rowsReturned = 0;
    return StatusCode.OK;
  }

  boolean isOwnedBy(SqlSession session) {
    return active && owner == session;
  }

  boolean implicitTransaction() {
    return implicitTransaction;
  }

  boolean valueIndex() {
    return valueIndex;
  }

  int projectedColumn(int index) {
    return projectedColumns[index];
  }

  int projectedColumnCount() {
    return projectedColumnCount;
  }

  void complete() {
    active = false;
  }

  void rowReturned() {
    rowsReturned++;
  }

  public long rowsReturned() {
    return rowsReturned;
  }

  public boolean isActive() {
    return active;
  }
}
