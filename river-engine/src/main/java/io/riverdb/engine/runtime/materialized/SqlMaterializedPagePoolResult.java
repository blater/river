package io.riverdb.engine.runtime.materialized;

/** Caller-owned publication carrier for page-pool construction. */
public final class SqlMaterializedPagePoolResult {
  private SqlMaterializedPagePool pool;

  public void reset() { pool = null; }
  void set(SqlMaterializedPagePool value) { pool = value; }
  public SqlMaterializedPagePool pool() { return pool; }
}
