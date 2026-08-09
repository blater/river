package io.riverdb.engine.page;

/** Caller-owned output for creating or opening the first page store. */
public final class SinglePageStoreOpenResult {
  private SinglePageStore store;

  public SinglePageStore store() {
    return store;
  }

  public void set(SinglePageStore value) {
    store = value;
  }

  public void reset() {
    store = null;
  }
}
