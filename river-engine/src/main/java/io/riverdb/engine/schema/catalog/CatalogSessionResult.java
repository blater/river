package io.riverdb.engine.schema.catalog;

import io.riverdb.engine.table.IndexedTransactionSession;

/** Reusable exact result for catalog embedded-session acquisition. */
final class CatalogSessionResult {
  private IndexedTransactionSession session;

  void reset() { session = null; }
  void set(IndexedTransactionSession value) { session = value; }
  IndexedTransactionSession session() { return session; }
}
