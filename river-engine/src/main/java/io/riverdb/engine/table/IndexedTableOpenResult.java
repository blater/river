package io.riverdb.engine.table;

/** Caller-owned result for opening the first indexed table. */
public final class IndexedTableOpenResult {
  private IndexedTable table;

  public IndexedTable table() {
    return table;
  }

  public void set(IndexedTable value) {
    table = value;
  }

  public void reset() {
    table = null;
  }
}
