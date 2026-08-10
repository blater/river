package io.riverdb.engine.page;

/** Caller-owned result for opening the indexed page store. */
public final class IndexedPageStoreOpenResult {
  private IndexedPageStore store;

  public IndexedPageStore store() {
    return store;
  }

  public void set(IndexedPageStore value) {
    store = value;
  }

  public void reset() {
    store = null;
  }
}
