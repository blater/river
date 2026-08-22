package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalScanCursor;
import io.riverdb.engine.relational.RelationalScanResult;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.relational.ValueIndexLookupResult;
import io.riverdb.sql.SqlCommandType;
import io.riverdb.storage.heap.HeapRowResult;

/** Executes one prepared primary-key or unique-value point projection. */
final class SqlPointSelectExecution {
  private final RelationalSession session;
  private final BoundSqlStatement bound;
  private final SqlBoundPredicateEvaluator predicates;
  private final SqlRowProjectionEvaluator projections;
  private final SqlProjectionResultWriter results = new SqlProjectionResultWriter();
  private final SqlProjectedRow projected = new SqlProjectedRow();
  private final HeapRowResult fetched = new HeapRowResult();
  private final ValueIndexLookupResult indexed = new ValueIndexLookupResult();
  private final RelationalScanCursor cursor = new RelationalScanCursor();
  private final RelationalScanResult row = new RelationalScanResult();

  SqlPointSelectExecution(
      RelationalSession relationalSession,
      BoundSqlStatement statement,
      SqlBoundPredicateEvaluator predicateEvaluator,
      SqlRowProjectionEvaluator projectionEvaluator) {
    session = relationalSession;
    bound = statement;
    predicates = predicateEvaluator;
    projections = projectionEvaluator;
  }

  StatusCode execute(SqlExecutionResult result) {
    BoundSqlQuery.Block command = bound.executableQuery.root();
    boolean safePointAccess = safePointAccess(command);
    if (!selectCommand(command)
        || !safePointAccess && bound.executableQuery.edgeCount() == 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (!safePointAccess || bound.pointTextColumn > 0) {
      return executeScan(result);
    }
    long primaryKey;
    HeapRowResult source;
    StatusCode status;
    if (bound.predicateColumn == 0) {
      primaryKey = bound.accessValue;
      status = session.fetch(bound.table, primaryKey, fetched);
      source = fetched;
    } else {
      status = session.fetchByUniqueValue(
          bound.table, bound.predicateColumn, bound.accessValue, indexed);
      primaryKey = indexed.key();
      source = indexed.row();
    }
    if (status.isOk()) status = validateRow(source);
    if (status.isOk()) status = predicates.evaluate(primaryKey, source);
    if (status.isOk() && !predicates.matched()) status = StatusCode.CONFLICT;
    if (status.isOk()) {
      source = predicates.evaluatedRow(source);
      status = project(result, primaryKey, source);
    }
    if (status.isOk() || status == StatusCode.CONFLICT) predicates.releaseEvaluatedRow();
    return status;
  }

  boolean hasResources() {
    return cursor.isActive();
  }

  StatusCode closeResources() {
    if (!cursor.isActive()) return StatusCode.OK;
    StatusCode status = session.closeScan(cursor);
    if (status.isOk()) cursor.reset();
    return status;
  }

  private StatusCode executeScan(SqlExecutionResult result) {
    StatusCode status = session.beginScan(bound.table, cursor);
    boolean active = status.isOk();
    boolean found = false;
    while (status.isOk()) {
      status = session.nextScan(cursor, row);
      if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
        break;
      }
      HeapRowResult source = row.row();
      if (status.isOk()) status = validateRow(source);
      if (status.isOk()) status = predicates.evaluate(row.key(), source);
      if (status.isOk() && predicates.matched()) {
        source = predicates.evaluatedRow(source);
        status = project(result, row.key(), source);
        predicates.releaseEvaluatedRow();
        found = status.isOk();
        break;
      }
      if (status.isOk()) predicates.releaseEvaluatedRow();
    }
    status = finishScan(active, status);
    return status.isOk() && !found ? StatusCode.CONFLICT : status;
  }

  private StatusCode project(
      SqlExecutionResult result, long primaryKey, HeapRowResult source) {
    StatusCode status = projections.project(primaryKey, source, projected);
    return status.isOk()
        ? results.writePoint(result, primaryKey, source, bound, projected)
        : status;
  }

  private StatusCode finishScan(boolean active, StatusCode bodyStatus) {
    if (!active) return bodyStatus;
    if (predicates.hasResources()) {
      return bodyStatus.isOk() ? StatusCode.CONFLICT : bodyStatus;
    }
    StatusCode close = session.closeScan(cursor);
    if (close.isOk()) cursor.reset();
    return bodyStatus.isOk() ? close : bodyStatus;
  }

  private boolean hasEqualityAccess(BoundSqlQuery.Block command) {
    return bound.pointTextColumn > 0 || bound.accessPredicate >= 0
        && bound.accessComparison == io.riverdb.sql.SqlComparison.EQUAL;
  }

  private boolean safePointAccess(BoundSqlQuery.Block command) {
    if (!hasEqualityAccess(command)) return false;
    int column = accessColumn();
    return column == 0 || bound.table.hasUniqueIndexOn(column);
  }

  private static boolean selectCommand(BoundSqlQuery.Block command) {
    return command.type() == SqlCommandType.SELECT
        || command.type() == SqlCommandType.SCAN;
  }

  private int accessColumn() {
    return bound.pointTextColumn > 0 ? bound.pointTextColumn : bound.predicateColumn;
  }

  private StatusCode validateRow(HeapRowResult source) {
    return source.length() >= bound.table.fixedRowBytes()
            && source.length() <= bound.table.maximumRowBytes()
        ? StatusCode.OK : StatusCode.CORRUPTION;
  }
}
