package io.riverdb.engine.table;

/** Caller-owned result for one database-bound indexed transaction session. */
public final class IndexedTransactionSessionOpenResult {
  private IndexedTransactionSession session;

  public void reset() { session = null; }
  void set(IndexedTransactionSession value) { session = value; }
  public IndexedTransactionSession session() { return session; }
}
