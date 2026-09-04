package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalScanCursor;
import io.riverdb.engine.relational.RelationalScanResult;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.relational.ValueIndexLookupResult;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlComparison;
import io.riverdb.storage.heap.HeapRowResult;

/** Collects a session-budgeted stable key set for scan-based UPDATE and DELETE. */
final class SqlMutationKeyCollector {
  private final RelationalSession session;
  private final SqlBoundPredicateEvaluator predicates;
  private final RelationalScanCursor cursor = new RelationalScanCursor();
  private final RelationalScanResult scanRow = new RelationalScanResult();
  private final ValueIndexLookupResult indexRow = new ValueIndexLookupResult();
  private final SqlRetainedLongPages keys;

  private boolean indexedScan;

  SqlMutationKeyCollector(
      RelationalSession relationalSession,
      SqlBoundPredicateEvaluator evaluator,
      SqlSessionShapeBudget shapeBudget) {
    session = relationalSession;
    predicates = evaluator;
    keys = new SqlRetainedLongPages(shapeBudget);
  }

  StatusCode collect(SqlCommand command, BoundSqlStatement bound) {
    StatusCode status = close();
    if (!status.isOk()) {
      return status;
    }
    status = keys.begin();
    if (status.isOk()) status = begin(command, bound);
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
    return keys.count();
  }

  long key(int index) {
    return keys.get(index);
  }

  void finish() { keys.finish(); }

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
    boolean equality = bounded && bound.accessComparison == SqlComparison.EQUAL;
    indexedScan = bounded && bound.predicateColumn > 0
        && bound.table.hasIndexOn(bound.predicateColumn);
    boolean primaryRange = bounded && bound.predicateColumn == 0;
    if (!indexedScan && !primaryRange) {
      return session.beginScan(bound.table, cursor);
    }
    long lower = equality
        ? accessValue(command, bound) : accessLower(command, bound);
    if (equality) {
      return indexedScan
          ? session.beginExactValueScan(
              bound.table, bound.predicateColumn, lower, cursor)
          : session.beginExactScan(bound.table, lower, cursor);
    }
    long upper = accessUpper(command, bound);
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
    if (status.isOk()) status = predicates.evaluate(key, row);
    if (!status.isOk() || !predicates.matched()) {
      return status;
    }
    status = keys.append(key);
    return status;
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
