package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.sql.SqlJoinChain;
import io.riverdb.storage.heap.HeapRowResult;

/** Reusable role-indexed JOIN tuple with owned rows required by nested cursors. */
final class SqlJoinRoleRows {
  private SqlBoundJoinContext context;
  private final long[] keys = new long[SqlJoinChain.MAXIMUM_JOIN_ROLES];
  private final HeapRowResult[] borrowed =
      new HeapRowResult[SqlJoinChain.MAXIMUM_JOIN_ROLES];
  private final SqlJoinOuterRow[] owned =
      new SqlJoinOuterRow[SqlJoinChain.MAXIMUM_JOIN_ROLES];
  private final boolean[] owns = new boolean[SqlJoinChain.MAXIMUM_JOIN_ROLES];
  private final boolean[] nulls = new boolean[SqlJoinChain.MAXIMUM_JOIN_ROLES];

  SqlJoinRoleRows() {
    for (int role = 0; role < owned.length; role++) owned[role] = new SqlJoinOuterRow();
  }

  void configure(SqlBoundJoinContext joinContext) { context = joinContext; }

  void borrow(int role, long key, HeapRowResult row) {
    clear(role);
    keys[role] = key;
    borrowed[role] = row;
  }

  void setNull(int role) {
    clear(role);
    nulls[role] = true;
  }

  StatusCode own(int role) {
    if (nulls[role] || owns[role]) return StatusCode.OK;
    StatusCode status = owned[role].capture(borrowed[role]);
    if (status.isOk()) {
      owns[role] = true;
      borrowed[role] = null;
    }
    return status;
  }

  StatusCode ownThrough(int lastRole) {
    for (int role = 0; role <= lastRole; role++) {
      StatusCode status = own(role);
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  long key(int role) { return keys[role]; }
  HeapRowResult row(int role) {
    return nulls[role] ? null : owns[role] ? owned[role].row() : borrowed[role];
  }
  TableDefinition table(int role) { return context.table(role); }
  boolean nullRole(int role) { return nulls[role]; }

  void clearFrom(int firstRole) {
    for (int role = firstRole; role < keys.length; role++) clear(role);
  }

  void reset() { clearFrom(0); }

  private void clear(int role) {
    owned[role].reset();
    keys[role] = 0;
    borrowed[role] = null;
    owns[role] = false;
    nulls[role] = false;
  }
}
