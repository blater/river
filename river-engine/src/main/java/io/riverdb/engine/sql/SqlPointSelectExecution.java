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
  private final RelationalScanCursor textCursor = new RelationalScanCursor();
  private final RelationalScanResult textRow = new RelationalScanResult();

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
    if ((command.type() != SqlCommandType.SELECT
            && command.type() != SqlCommandType.SCAN)
        || !hasEqualityAccess(command)
        || accessColumn() > 0
            && !bound.table.hasUniqueIndexOn(accessColumn())) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (bound.pointTextColumn > 0) {
      return executeText(result);
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
    if (status.isOk()) status = project(result, primaryKey, source);
    return status;
  }

  boolean hasResources() {
    return textCursor.isActive();
  }

  StatusCode closeResources() {
    if (!textCursor.isActive()) return StatusCode.OK;
    StatusCode status = session.closeScan(textCursor);
    if (status.isOk()) textCursor.reset();
    return status;
  }

  private StatusCode executeText(SqlExecutionResult result) {
    StatusCode status = session.beginScan(bound.table, textCursor);
    boolean active = status.isOk();
    boolean found = false;
    while (status.isOk()) {
      status = session.nextScan(textCursor, textRow);
      if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
        break;
      }
      HeapRowResult source = textRow.row();
      if (status.isOk()) status = validateRow(source);
      if (status.isOk()) status = predicates.evaluate(textRow.key(), source);
      if (status.isOk() && predicates.matched()) {
        status = project(result, textRow.key(), source);
        found = status.isOk();
        break;
      }
    }
    status = finishText(active, status);
    return status.isOk() && !found ? StatusCode.CONFLICT : status;
  }

  private StatusCode project(
      SqlExecutionResult result, long primaryKey, HeapRowResult source) {
    StatusCode status = projections.project(primaryKey, source, projected);
    return status.isOk()
        ? results.writePoint(result, primaryKey, source, bound, projected)
        : status;
  }

  private StatusCode finishText(boolean active, StatusCode bodyStatus) {
    if (!active) return bodyStatus;
    StatusCode close = session.closeScan(textCursor);
    if (close.isOk()) textCursor.reset();
    return bodyStatus.isOk() ? close : bodyStatus;
  }

  private boolean hasEqualityAccess(BoundSqlQuery.Block command) {
    return bound.pointTextColumn > 0 || bound.accessPredicate >= 0
        && bound.accessComparison == io.riverdb.sql.SqlComparison.EQUAL;
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
