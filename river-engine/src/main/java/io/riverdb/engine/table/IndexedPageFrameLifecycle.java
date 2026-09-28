package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;

/** Owns cache shutdown validation and coordinated detachment of all frame arenas. */
final class IndexedPageFrameLifecycle {
  private final IndexedPageFrameCache cache;
  private final IndexedPreparedPageBatch prepared;
  private final IndexedCurrentPageFrameStore current;
  private final IndexedStagingPageFrameStore staging;
  private final IndexedPageDurabilityChains durability;

  IndexedPageFrameLifecycle(
      IndexedPageFrameCache owner, IndexedPreparedPageBatch preparedBatch,
      IndexedCurrentPageFrameStore currentStore, IndexedStagingPageFrameStore stagingStore,
      IndexedPageDurabilityChains durabilityChains) {
    cache = owner;
    prepared = preparedBatch;
    current = currentStore;
    staging = stagingStore;
    durability = durabilityChains;
  }

  StatusCode detach() {
    if (prepared.active() || pinnedCurrentFrame() || pinnedStagingFrame()) {
      return StatusCode.CONFLICT;
    }
    StatusCode status = prepared.detach();
    if (!status.isOk()) return status;
    detachArenas();
    return StatusCode.OK;
  }

  void abandon() {
    prepared.abandon();
    detachArenas();
  }

  private boolean pinnedCurrentFrame() {
    for (IndexedPageFrame frame : current.frames()) {
      if (frame != null && (frame.pinCount != 0 || frame.publicationReserved)) return true;
    }
    return false;
  }

  private boolean pinnedStagingFrame() {
    for (IndexedPageFrame frame : staging.frames()) {
      if (frame != null && frame.pinCount != 0) return true;
    }
    return false;
  }

  private void detachArenas() {
    current.detach();
    staging.detach();
    durability.detach();
    cache.syncFrameViews();
  }
}
