package io.riverdb.format.catalog;

/** Caller-owned decoded continuation allocation authority. */
public final class CatalogAllocationWatermark {
  private long nextAllocation;
  private boolean available;

  void set(long next) {
    nextAllocation = next;
    available = true;
  }

  public void reset() {
    nextAllocation = 0;
    available = false;
  }

  public long nextAllocation() {
    return nextAllocation;
  }

  public boolean isAvailable() {
    return available;
  }
}
