package io.riverdb.storage.heap;

/** Caller-owned scan position over stable heap-page contents. */
public final class HeapScanCursor {
  private int nextSlot;

  public int nextSlot() {
    return nextSlot;
  }

  public void advance() {
    nextSlot++;
  }

  public void reset() {
    nextSlot = 0;
  }
}
