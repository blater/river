package io.riverdb.format.btree;

/** Caller-owned result of validated tuple comparison. */
public final class TupleComparison {
  private int value;

  void set(int comparison) {
    value = Integer.compare(comparison, 0);
  }

  public void reset() {
    value = 0;
  }

  public int value() {
    return value;
  }
}
