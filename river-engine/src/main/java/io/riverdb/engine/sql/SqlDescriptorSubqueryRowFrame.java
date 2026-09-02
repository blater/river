package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.relational.TableDefinition;

/** Reusable descriptor scan and canonical block row for one nested query block. */
final class SqlDescriptorSubqueryRowFrame {
  private final StatusDetail detail = new StatusDetail(128);
  private final TableDefinition binding = new TableDefinition();
  private final SqlUniversalDescriptorJoinRole rows;
  private boolean available;
  private boolean active;

  SqlDescriptorSubqueryRowFrame(RelationalSession relationalSession) {
    rows = new SqlUniversalDescriptorJoinRole(relationalSession);
  }

  StatusCode prepare(CharSequence name) {
    StatusCode status = rows.reset();
    binding.reset();
    detail.reset();
    if (status.isOk()) status = rows.resolve(name, binding, detail);
    return status;
  }

  StatusCode begin() {
    available = false;
    StatusCode status = rows.open();
    if (status.isOk()) active = true;
    return status;
  }

  void configureRoot(
      io.riverdb.sql.SqlCommand command, SqlBoundBooleanPredicateProgram where) {
    rows.configureRoot(command, where);
  }

  void configureRoot(SqlUniversalDescriptorIndexAccess access) {
    rows.configureRoot(access);
  }

  StatusCode next() {
    available = false;
    StatusCode status = active ? rows.next() : StatusCode.CONFLICT;
    if (status.isOk()) available = true;
    return status;
  }

  StatusCode closeScan() {
    StatusCode status = rows.closeScan();
    if (status.isOk()) {
      available = false;
      active = false;
    }
    return status;
  }

  StatusCode reset() {
    StatusCode status = closeScan();
    if (status.isOk()) {
      status = rows.reset();
      binding.reset();
    }
    return status;
  }

  boolean active() { return active; }
  boolean available() { return available; }
  long key() { return rows.key(); }
  long publicKey() { return rows.publicKey(); }
  int accessColumn() { return rows.accessColumn(); }
  SqlBlockRow row() { return rows.row(); }
}
