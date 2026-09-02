package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalScanCursor;
import io.riverdb.engine.relational.RelationalScanResult;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.relational.TableDefinition;

/** Retained physical scan and decoded row for one legacy universal-join role. */
final class SqlUniversalLegacyJoinRole {
  private final RelationalSession session;
  private final RelationalScanCursor cursor = new RelationalScanCursor();
  private final RelationalScanResult result = new RelationalScanResult();
  private final SqlBlockPhysicalRowReader reader = new SqlBlockPhysicalRowReader();
  private final SqlBlockRow row = new SqlBlockRow();
  private TableDefinition table;
  private long key;

  SqlUniversalLegacyJoinRole(RelationalSession relationalSession) {
    session = relationalSession;
  }

  StatusCode resolve(CharSequence name, TableDefinition binding) {
    table = binding;
    StatusCode status = session.resolveTable(name, binding);
    return status.isOk() ? reader.prepare(binding, row) : status;
  }

  StatusCode open() {
    key = 0;
    result.reset();
    return session.beginScan(table, cursor);
  }

  StatusCode next() {
    StatusCode status = session.nextScan(cursor, result);
    if (!status.isOk()) {
      key = 0;
      return status;
    }
    key = result.key();
    return reader.read(key, result.row(), table, row);
  }

  StatusCode closeScan() {
    StatusCode status = cursor.isActive() ? session.closeScan(cursor) : StatusCode.OK;
    if (status.isOk() && !cursor.isActive()) status = cursor.reset();
    key = 0;
    return status;
  }

  StatusCode reset() {
    StatusCode status = closeScan();
    if (status.isOk()) {
      table = null;
      result.reset();
      reader.reset();
    }
    return status;
  }

  long key() { return key; }
  SqlBlockRow row() { return row; }
}
