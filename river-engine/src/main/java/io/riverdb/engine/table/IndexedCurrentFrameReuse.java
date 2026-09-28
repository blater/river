package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;

/** Owns version-chain unlinking and the pin-aware current-frame reuse policy. */
final class IndexedCurrentFrameReuse {
  private final IndexedPageFrameCache cache;
  private final IndexedPageState state;
  private final IndexedPageFrameIo io;
  private final IndexedPageFrame[] frames;
  private final IndexedPageFrameMap map;
  private int probeCursor;
  private int reclaimedFrameHead = -1;
  private boolean detached;

  IndexedCurrentFrameReuse(
      IndexedPageFrameCache owner, IndexedPageState pageState, IndexedPageFrameIo frameIo,
      IndexedPageFrame[] currentFrames, IndexedPageFrameMap currentMap) {
    cache = owner;
    state = pageState;
    io = frameIo;
    frames = currentFrames;
    map = currentMap;
  }

  void detach() {
    reclaimedFrameHead = -1;
    detached = true;
  }

  int reusable(boolean allowEviction, long oldestVisibleCommitSequence) {
    if (detached) return -1;
    if (reclaimedFrameHead >= 0) {
      int slot = reclaimedFrameHead;
      IndexedPageFrame frame = frames[slot];
      reclaimedFrameHead = frame.previousVersionSlot;
      frame.previousVersionSlot = -1;
      return slot;
    }
    for (int probe = 0; probe < frames.length; probe++) {
      int index = probeCursor;
      probeCursor = (probeCursor + 1) % frames.length;
      IndexedPageFrame frame = frames[index];
      if (frame == null || frame.pageId == 0 && !frame.publicationReserved) return index;
      if (frame.publicationReserved || frame.pinCount != 0) continue;
      if (frame.nextVersionSlot >= 0
          && frame.validUntilCommitSequence <= oldestVisibleCommitSequence) return index;
      if (allowEviction && !state.staged(frame.pageId)
          && frame.nextVersionSlot < 0 && frame.previousVersionSlot < 0) return index;
    }
    return -1;
  }

  StatusCode prepare(int slot) {
    IndexedPageFrame frame = frames[slot];
    if (frame == null || frame.pinCount != 0 || frame.publicationReserved) {
      return StatusCode.INVARIANT_BROKEN;
    }
    if (frame.nextVersionSlot >= 0) return unlinkHistorical(frame, slot);
    return evictCurrent(frame, slot);
  }

  StatusCode reclaim(long oldestVisibleCommitSequence) {
    if (oldestVisibleCommitSequence < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    for (int slot = 0; slot < frames.length; slot++) {
      IndexedPageFrame frame = frames[slot];
      if (!reclaimable(frame, oldestVisibleCommitSequence)) continue;
      StatusCode status = prepare(slot);
      if (!status.isOk()) return status;
      frame.previousVersionSlot = reclaimedFrameHead;
      reclaimedFrameHead = slot;
    }
    return StatusCode.OK;
  }

  IndexedPageFrame frameAt(int slot) {
    IndexedPageFrame frame = frames[slot];
    if (frame != null) return frame;
    try {
      frame = new IndexedPageFrame();
      frames[slot] = frame;
      return frame;
    } catch (OutOfMemoryError error) {
      cache.setStatus(StatusCode.RESOURCE_EXHAUSTED);
      return null;
    }
  }

  private StatusCode unlinkHistorical(IndexedPageFrame frame, int slot) {
    IndexedPageFrame newer = frames[frame.nextVersionSlot];
    if (newer == null || newer.previousVersionSlot != slot) {
      return StatusCode.INVARIANT_BROKEN;
    }
    newer.previousVersionSlot = frame.previousVersionSlot;
    if (frame.previousVersionSlot < 0) return clear(frame);
    IndexedPageFrame older = frames[frame.previousVersionSlot];
    if (older == null || older.nextVersionSlot != slot) return StatusCode.INVARIANT_BROKEN;
    older.nextVersionSlot = frame.nextVersionSlot;
    return clear(frame);
  }

  private StatusCode evictCurrent(IndexedPageFrame frame, int slot) {
    if (frame.previousVersionSlot >= 0 || map.find(frame.pageId) != slot) {
      return StatusCode.INVARIANT_BROKEN;
    }
    StatusCode status = io.writeBack(frame);
    return status.isOk() ? clear(frame) : status;
  }

  private StatusCode clear(IndexedPageFrame frame) {
    if (frame.nextVersionSlot < 0) map.remove(frame.pageId);
    frame.pageId = 0;
    frame.dirty = false;
    frame.recordStart = 0;
    frame.recordEnd = 0;
    frame.clearGeneration();
    return StatusCode.OK;
  }

  private boolean reclaimable(IndexedPageFrame frame, long oldestVisibleCommitSequence) {
    return frame != null && frame.pageId != 0 && frame.pinCount == 0
        && !frame.publicationReserved && frame.nextVersionSlot >= 0
        && frame.validUntilCommitSequence <= oldestVisibleCommitSequence;
  }
}
