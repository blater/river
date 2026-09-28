package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;

/** Owns transient ownership chains for prepared frames handed to durability. */
final class IndexedPageDurabilityChains {
  private final IndexedPageFrameCache cache;
  private long[] ownerTokens;
  private int[] nextSlots;

  IndexedPageDurabilityChains(IndexedPageFrameCache owner, int frameCount) {
    cache = owner;
    ownerTokens = new long[frameCount];
    nextSlots = new int[frameCount];
    java.util.Arrays.fill(nextSlots, -1);
  }

  StatusCode release(long ownerToken, int head) {
    if (ownerToken <= 0 || head < -1 || head >= cache.currentFrames.length) {
      return cache.setStatus(StatusCode.INVALID_EXTERNAL_INPUT);
    }
    int slot = head;
    int visited = 0;
    while (slot >= 0) {
      if (slot >= ownerTokens.length || ownerTokens[slot] != ownerToken
          || ++visited > ownerTokens.length) {
        return cache.setStatus(StatusCode.INVARIANT_BROKEN);
      }
      slot = nextSlots[slot];
    }
    slot = head;
    while (slot >= 0) {
      int next = nextSlots[slot];
      ownerTokens[slot] = 0;
      nextSlots[slot] = -1;
      IndexedPreparedPageBatch.releaseTransferredFrame(cache, slot);
      slot = next;
    }
    return cache.setStatus(StatusCode.OK);
  }

  boolean available(int slot) {
    return slot >= 0 && slot < cache.currentFrames.length
        && ownerTokens[slot] == 0 && nextSlots[slot] == -1;
  }

  void claim(int slot, long ownerToken, int nextSlot) {
    ownerTokens[slot] = ownerToken;
    nextSlots[slot] = nextSlot;
  }

  void detach() {
    ownerTokens = new long[0];
    nextSlots = new int[0];
  }
}
