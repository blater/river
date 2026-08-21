package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalScanCursor;
import io.riverdb.engine.relational.RelationalScanResult;

/** Owns the physical cursor and validates rows before the first block boundary. */
final class SqlBlockSource {
  private final io.riverdb.engine.relational.RelationalSession session;
  private final BoundSqlStatement bound;
  private final SqlRowProjectionEvaluator projections;
  private final SqlBlockPhysicalRowReader physical = new SqlBlockPhysicalRowReader();
  private final RelationalScanCursor cursor = new RelationalScanCursor();
  private final RelationalScanResult result = new RelationalScanResult();
  private final SqlJoinChainSource join;

  SqlBlockSource(
      io.riverdb.engine.relational.RelationalSession relationalSession,
      BoundSqlStatement statement,
      SqlJoinChainSource joinSource,
      SqlRowProjectionEvaluator projectionEvaluator) {
    session = relationalSession;
    bound = statement;
    join = joinSource;
    projections = projectionEvaluator;
  }

  StatusCode begin(SqlBlockRowStore input) {
    return input == null ? session.beginScan(bound.table, cursor) : StatusCode.OK;
  }

  StatusCode next(SqlBlockRowStore input, SqlBlockRow row) {
    if (input != null) return input.next(row);
    StatusCode status = session.nextScan(cursor, result);
    return status.isOk()
        ? physical.read(result.key(), result.row(), bound.table, row) : status;
  }

  StatusCode finish(SqlBlockRowStore input, StatusCode status) {
    StatusCode closed = input == null
        ? cursor.isActive() ? session.closeScan(cursor) : StatusCode.OK
        : input.close();
    return status.isOk() ? closed : status;
  }

  StatusCode beginJoin() {
    return join.begin();
  }

  void resetJoinMetrics() { join.resetMetrics(); }

  StatusCode nextJoin(SqlBlockRow row) {
    if (row == null) return StatusCode.CONFLICT;
    StatusCode status = join.next();
    if (status.isOk()) {
      status = projections.projectJoin(
          join.rows(),
          row);
    }
    if (!status.isOk() && status != StatusCode.CONFLICT) row.reset(0);
    return status;
  }

  StatusCode finishJoin(StatusCode status) {
    StatusCode closed = join.close();
    return status.isOk() ? closed : status;
  }

  boolean hasResources() {
    return cursor.isActive() || join.hasResources();
  }

  StatusCode close() {
    StatusCode status = cursor.isActive() ? session.closeScan(cursor) : StatusCode.OK;
    if (status.isOk()) status = cursor.reset();
    if (status.isOk()) status = join.close();
    physical.reset();
    return status;
  }
}
