package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalScanCursor;
import io.riverdb.engine.relational.RelationalScanResult;

/** Owns the physical cursor and validates rows before the first block boundary. */
final class SqlBlockSource {
  private final io.riverdb.engine.relational.RelationalSession session;
  private final BoundSqlStatement bound;
  private final SqlBlockPhysicalRowReader physical = new SqlBlockPhysicalRowReader();
  private final RelationalScanCursor cursor = new RelationalScanCursor();
  private final RelationalScanResult result = new RelationalScanResult();

  SqlBlockSource(
      io.riverdb.engine.relational.RelationalSession relationalSession,
      BoundSqlStatement statement) {
    session = relationalSession;
    bound = statement;
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

  boolean hasResources() { return cursor.isActive(); }

  StatusCode close() {
    StatusCode status = cursor.isActive() ? session.closeScan(cursor) : StatusCode.OK;
    if (status.isOk()) status = cursor.reset();
    physical.reset();
    return status;
  }
}
