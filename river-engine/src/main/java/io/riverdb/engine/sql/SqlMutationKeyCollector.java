package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalScanCursor;
import io.riverdb.engine.relational.RelationalScanResult;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.relational.ValueIndexLookupResult;
import io.riverdb.sql.SqlCommand;
import io.riverdb.storage.heap.HeapRowResult;

/** Collects a bounded stable key set for scan-based UPDATE and DELETE. */
final class SqlMutationKeyCollector {
  private final RelationalSession session;
  private final SqlExpressionEvaluator expressions;
  private final RelationalScanCursor cursor = new RelationalScanCursor();
  private final RelationalScanResult scanRow = new RelationalScanResult();
  private final ValueIndexLookupResult indexRow = new ValueIndexLookupResult();
  private final long[] keys = new long[SqlCommand.MAXIMUM_INSERT_ROWS];

  private int count;
  private boolean indexedScan;

  SqlMutationKeyCollector(
      RelationalSession relationalSession, SqlExpressionEvaluator evaluator) {
    session = relationalSession;
    expressions = evaluator;
  }

  StatusCode collect(SqlCommand command, BoundSqlStatement bound) {
    StatusCode status = close();
    if (!status.isOk()) {
      return status;
    }
    count = 0;
    status = begin(command, bound);
    boolean active = status.isOk();
    while (status.isOk()) {
      status = next(command, bound);
      if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
        break;
      }
    }
    return active ? closeAfter(status) : status;
  }

  int count() {
    return count;
  }

  long key(int index) {
    return keys[index];
  }

  boolean hasOpenResources() {
    return cursor.isActive();
  }

  StatusCode close() {
    if (!cursor.isActive()) {
      return StatusCode.OK;
    }
    StatusCode status = session.closeScan(cursor);
    return status.isOk() ? cursor.reset() : status;
  }

  private StatusCode begin(SqlCommand command, BoundSqlStatement bound) {
    boolean bounded = bound.accessPredicate >= 0;
    boolean equality = bounded && accessEquality(command, bound);
    indexedScan = bounded && bound.predicateColumn > 0
        && bound.table.hasIndexOn(bound.predicateColumn);
    boolean primaryRange = bounded && bound.predicateColumn == 0;
    if ((indexedScan || primaryRange) && equality
        && accessValue(command, bound) == Long.MAX_VALUE) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (!indexedScan && !primaryRange) {
      return session.beginScan(bound.table, cursor);
    }
    long lower = equality
        ? accessValue(command, bound) : accessLower(command, bound);
    long upper = equality
        ? accessValue(command, bound) + 1 : accessUpper(command, bound);
    return indexedScan
        ? session.beginValueScan(
            bound.table, bound.predicateColumn, lower, upper, cursor)
        : session.beginScan(bound.table, lower, upper, cursor);
  }

  private StatusCode next(SqlCommand command, BoundSqlStatement bound) {
    StatusCode status = indexedScan
        ? session.nextValueScan(bound.table, cursor, scanRow, indexRow)
        : session.nextScan(cursor, scanRow);
    if (!status.isOk()) {
      return status;
    }
    HeapRowResult row = indexedScan ? indexRow.row() : scanRow.row();
    long key = indexedScan ? indexRow.key() : scanRow.key();
    status = validateRow(row, bound);
    if (!status.isOk() || !matchesPredicates(command, bound, key, row)) {
      return status;
    }
    if (count >= keys.length) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    keys[count++] = key;
    return StatusCode.OK;
  }

  private boolean matchesPredicates(
      SqlCommand command,
      BoundSqlStatement bound,
      long primaryKey,
      HeapRowResult row) {
    boolean conjunction = true;
    for (int index = 0; index < bound.predicateCount; index++) {
      if (command.predicateStartsDisjunction(index)) {
        if (conjunction) {
          return true;
        }
        conjunction = true;
      }
      if (conjunction) {
        conjunction = matchesPredicate(command, bound, primaryKey, row, index);
      }
    }
    return conjunction;
  }

  private boolean matchesPredicate(
      SqlCommand command,
      BoundSqlStatement bound,
      long primaryKey,
      HeapRowResult row,
      int index) {
    int column = bound.predicateColumns[index];
    boolean nullValue = expressions.isNull(row, bound.table, column);
    if (command.isNullPredicate(index)) {
      return nullValue != command.isNullPredicateNegated(index);
    }
    if (nullValue) {
      return false;
    }
    if (bound.table.isVarchar(column)) {
      return expressions.matchesTextComparison(
          row, bound.table, column, command, index);
    }
    long value = expressions.readColumn(primaryKey, row, column);
    return expressions.matchesComparison(
        value, bound.table.typeDescriptor(column), command, index);
  }

  private StatusCode closeAfter(StatusCode body) {
    StatusCode close = session.closeScan(cursor);
    StatusCode status = body.isOk() ? close : body;
    if (close.isOk()) {
      cursor.reset();
    }
    return status;
  }

  private static StatusCode validateRow(
      HeapRowResult row, BoundSqlStatement bound) {
    return row.length() < bound.table.fixedRowBytes()
            || row.length() > bound.table.maximumRowBytes()
        ? StatusCode.CORRUPTION : StatusCode.OK;
  }

  private static boolean accessEquality(
      SqlCommand command, BoundSqlStatement bound) {
    return command.isEqualityPredicate(bound.accessPredicate);
  }

  private static long accessValue(
      SqlCommand command, BoundSqlStatement bound) {
    return bound.accessValue;
  }

  private static long accessLower(
      SqlCommand command, BoundSqlStatement bound) {
    return bound.accessLowerInclusive;
  }

  private static long accessUpper(
      SqlCommand command, BoundSqlStatement bound) {
    return bound.accessUpperExclusive;
  }
}
