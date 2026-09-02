package io.riverdb.engine.table;

/** Caller-owned result for opening the indexed page store. */
public final class IndexedTableStoreOpenResult {
  private IndexedTableStore store;

  public IndexedTableStore store() {
    return store;
  }

  public void set(IndexedTableStore value) {
    store = value;
  }

  public void reset() {
    store = null;
  }

}
