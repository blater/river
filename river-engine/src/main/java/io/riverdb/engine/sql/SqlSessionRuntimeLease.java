package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.runtime.SqlRuntimeLease;

/** Owns the optional database runtime lease retained by one SQL session. */
final class SqlSessionRuntimeLease {
  private SqlRuntimeLease lease;

  void claim(SqlRuntimeLease claimed) {
    lease = claimed;
  }

  StatusCode close() {
    if (lease == null) return StatusCode.OK;
    StatusCode status = lease.close();
    if (status.isOk()) lease = null;
    return status;
  }
}
