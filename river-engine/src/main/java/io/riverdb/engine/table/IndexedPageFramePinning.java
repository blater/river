package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;

/** Owns current-frame pins, historical-generation pins, and buffer retention. */
final class IndexedPageFramePinning {
  private final IndexedPageFrameCache cache;
  private final IndexedPageState state;
  private final IndexedCurrentPageFrameStore current;

  IndexedPageFramePinning(
      IndexedPageFrameCache owner, IndexedPageState pageState,
      IndexedCurrentPageFrameStore currentStore) {
    cache = owner;
    state = pageState;
    current = currentStore;
  }

  StatusCode pinCurrentPage(int pageId) {
    if (!IndexedPageFrameCache.validPageId(pageId) || !state.present(pageId)) {
      return cache.setStatus(StatusCode.CORRUPTION);
    }
    IndexedPageFrame frame = current.currentFrame(pageId, true);
    if (frame == null) return cache.lastStatus();
    frame.pinCount++;
    return cache.setStatus(StatusCode.OK);
  }

  StatusCode pinPageAt(
      int pageId, long visibleCommitSequence, IndexedPageGenerationPin result) {
    if (!IndexedPageFrameCache.validPageId(pageId) || visibleCommitSequence < 0
        || result == null || result.active() || !state.present(pageId)) {
      return cache.setStatus(StatusCode.INVALID_EXTERNAL_INPUT);
    }
    IndexedPageFrame currentFrame = current.currentFrame(pageId, true);
    if (currentFrame == null) return cache.lastStatus();
    int slot = current.map().find(pageId);
    while (slot >= 0) {
      IndexedPageFrame frame = current.frames()[slot];
      if (frame.validFromCommitSequence <= visibleCommitSequence
          && visibleCommitSequence < frame.validUntilCommitSequence) {
        frame.pinCount++;
        current.touch(frame);
        result.set(
            slot, pageId, frame.validFromCommitSequence, frame.pageGeneration,
            frame.payload, frame.payloadKind, frame.ownerKeyId);
        return cache.setStatus(StatusCode.OK);
      }
      slot = frame.previousVersionSlot;
    }
    return cache.setStatus(StatusCode.CORRUPTION);
  }

  StatusCode unpinPage(IndexedPageGenerationPin pin) {
    if (pin == null || !pin.active() || pin.slot() >= current.frames().length) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    IndexedPageFrame frame = current.frames()[pin.slot()];
    if (frame == null || frame.pageId != pin.pageId()
        || frame.validFromCommitSequence != pin.validFromCommitSequence()
        || frame.pinCount <= 0 || frame.payload != pin.payload()) {
      return StatusCode.INVARIANT_BROKEN;
    }
    frame.pinCount--;
    pin.reset();
    return StatusCode.OK;
  }

  StatusCode ensureBuffers(int pageId) {
    if (!IndexedPageFrameCache.validPageId(pageId)) {
      return cache.setStatus(StatusCode.INVALID_EXTERNAL_INPUT);
    }
    IndexedPageFrame frame = current.currentFrame(pageId, state.present(pageId));
    if (frame == null && !state.present(pageId)) frame = current.acquire(pageId, true);
    if (frame == null) return cache.lastStatus();
    return cache.setStatus(StatusCode.OK);
  }

  StatusCode retainBuffer(int pageId) {
    StatusCode status = ensureBuffers(pageId);
    if (!status.isOk()) return status;
    int slot = current.map().find(pageId);
    if (slot < 0) return cache.setStatus(StatusCode.INVARIANT_BROKEN);
    current.frames()[slot].pinCount++;
    return StatusCode.OK;
  }

  void releaseBuffer(int pageId) {
    int slot = current.map().find(pageId);
    if (slot >= 0 && current.frames()[slot].pinCount > 0) {
      current.frames()[slot].pinCount--;
    }
  }
}
