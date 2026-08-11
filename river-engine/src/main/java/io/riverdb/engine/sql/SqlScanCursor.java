package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalScanCursor;
import io.riverdb.engine.relational.TableSchema;

/** Caller-owned capability for one ordered SQL table scan. */
public final class SqlScanCursor {
  private final RelationalScanCursor relational = new RelationalScanCursor();
  private SqlSession owner;
  private boolean aggregate;
  private boolean aggregateTransactionActive;
  private long aggregateValue;
  private long aggregateCommitSequence;
  private boolean implicitTransaction;
  private boolean valueIndex;
  private int filterColumn = -1;
  private long filterLowerInclusive;
  private long filterUpperExclusive;
  private boolean equalityFilter;
  private final int[] projectedColumns = new int[TableSchema.MAXIMUM_COLUMNS];
  private int projectedColumnCount;
  private boolean active;
  private long rowsReturned;

  public StatusCode reset() {
    if (active) {
      return StatusCode.CONFLICT;
    }
    owner = null;
    aggregate = false;
    aggregateTransactionActive = false;
    aggregateValue = 0;
    aggregateCommitSequence = 0;
    implicitTransaction = false;
    valueIndex = false;
    filterColumn = -1;
    filterLowerInclusive = 0;
    filterUpperExclusive = 0;
    equalityFilter = false;
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
      int scanFilterColumn,
      long lowerInclusive,
      long upperExclusive,
      boolean equality,
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
    filterColumn = scanFilterColumn;
    filterLowerInclusive = lowerInclusive;
    filterUpperExclusive = upperExclusive;
    equalityFilter = equality;
    projectedColumnCount = projectionCount;
    for (int index = 0; index < projectionCount; index++) {
      projectedColumns[index] = projections[index];
    }
    active = true;
    rowsReturned = 0;
    return StatusCode.OK;
  }

  StatusCode claimAggregate(
      SqlSession session,
      long value,
      boolean transactionActive,
      long commitSequence) {
    if (active || session == null || commitSequence < 0) {
      return StatusCode.CONFLICT;
    }
    owner = session;
    aggregate = true;
    aggregateValue = value;
    aggregateTransactionActive = transactionActive;
    aggregateCommitSequence = commitSequence;
    projectedColumnCount = 1;
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

  boolean aggregate() {
    return aggregate;
  }

  long aggregateValue() {
    return aggregateValue;
  }

  boolean aggregateTransactionActive() {
    return aggregateTransactionActive;
  }

  long aggregateCommitSequence() {
    return aggregateCommitSequence;
  }

  boolean filtersRows() {
    return filterColumn >= 0;
  }

  int filterColumn() {
    return filterColumn;
  }

  boolean matches(long value) {
    return equalityFilter
        ? value == filterLowerInclusive
        : value >= filterLowerInclusive && value < filterUpperExclusive;
  }

  public int projectedColumn(int index) {
    return index >= 0 && index < projectedColumnCount ? projectedColumns[index] : -1;
  }

  public int projectedColumnCount() {
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
