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
    if (status.isOk()) status = begin(bound);
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

  private StatusCode begin(BoundSqlStatement bound) {
    indexedScan = false;
    if (bound.accessPredicate < 0) return session.beginScan(bound.table, cursor);
    indexedScan = bound.predicateColumn > 0 && bound.table.hasIndexOn(bound.predicateColumn);
    if (!indexedScan && bound.predicateColumn != 0) {
      return session.beginScan(bound.table, cursor);
    }
    if (bound.accessComparison == SqlComparison.EQUAL) {
      return indexedScan
          ? session.beginExactValueScan(
              bound.table, bound.predicateColumn, bound.accessValue, cursor)
          : session.beginExactScan(bound.table, bound.accessValue, cursor);
    }
    return indexedScan
        ? session.beginValueScan(bound.table, bound.predicateColumn,
            bound.accessLowerInclusive, bound.accessUpperExclusive, cursor)
        : session.beginScan(bound.table, bound.accessLowerInclusive, bound.accessUpperExclusive, cursor);
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

}
