package io.riverdb.engine.runtime;

/** Caller-owned output for a database SQL-runtime lease. */
public final class SqlRuntimeLeaseResult {
  private SqlRuntimeLease lease;

  public void reset() {
    lease = null;
  }

  void set(SqlRuntimeLease acquired) {
    lease = acquired;
  }

  public SqlRuntimeLease lease() {
    return lease;
  }
}
