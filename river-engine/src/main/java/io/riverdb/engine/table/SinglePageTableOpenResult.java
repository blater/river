package io.riverdb.engine.table;

/** Caller-owned output for creating or opening the first heap table. */
public final class SinglePageTableOpenResult {
  private SinglePageTable table;

  public SinglePageTable table() {
    return table;
  }

  public void set(SinglePageTable value) {
    table = value;
  }

  public void reset() {
    table = null;
  }
}
