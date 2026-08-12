package io.riverdb.engine;

import io.riverdb.engine.table.IndexedTransactionSession;

/** Caller-owned output for an embedded transaction session. */
public final class EmbeddedSessionOpenResult {
  private IndexedTransactionSession session;

  public void reset() {
    session = null;
  }

  public void set(IndexedTransactionSession opened) {
    session = opened;
  }

  public IndexedTransactionSession session() {
    return session;
  }
}
