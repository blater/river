package io.riverdb.format.catalog;

/** Caller-owned result of reserving one monotonic continuation-key range. */
public final class CatalogContinuationReservation {
  private long firstKey;
  private long nextAllocation;

  void set(long first, long next) {
    firstKey = first;
    nextAllocation = next;
  }

  public void reset() { set(0, 0); }
  public long firstKey() { return firstKey; }
  public long nextAllocation() { return nextAllocation; }
}
