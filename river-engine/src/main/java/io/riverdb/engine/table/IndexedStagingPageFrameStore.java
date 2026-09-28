package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;

/** Owns the bounded staging arena and its durable shadow-frame lifecycle. */
final class IndexedStagingPageFrameStore {
  private static final IndexedPageFrame[] DETACHED = new IndexedPageFrame[0];

  private final IndexedPageFrameCache cache;
  private final IndexedPageState state;
  private final IndexedPageFrameIo io;
  private final IndexedCurrentPageFrameStore current;
  private final IndexedPreparedPageBatch prepared;
  private IndexedPageFrame[] frames;
  private IndexedPageFrameMap map;
  private long accessClock;

  IndexedStagingPageFrameStore(
      IndexedPageFrameCache owner, IndexedPageState pageState,
      IndexedPageFrameIo frameIo, IndexedCurrentPageFrameStore currentStore,
      IndexedPreparedPageBatch preparedBatch, int frameCount, int mapCapacity) {
    cache = owner;
    state = pageState;
    io = frameIo;
    current = currentStore;
    prepared = preparedBatch;
    frames = new IndexedPageFrame[frameCount];
    map = new IndexedPageFrameMap(mapCapacity);
  }

  IndexedPageFrame[] frames() { return frames; }
  IndexedPageFrameMap map() { return map; }

  void detach() {
    for (IndexedPageFrame frame : frames) {
      if (frame != null) frame.invalidatePageValidation();
    }
    map.detach();
    frames = DETACHED;
  }

  void abandon() { detach(); }

  IndexedPageFrame acquire(int pageId) {
    int existing = map.find(pageId);
    if (existing >= 0) return frames[existing];
    int slot = IndexedPageFrameSelection.reusable(frames, true);
    if (slot < 0) return fail(StatusCode.RESOURCE_EXHAUSTED);
    IndexedPageFrame frame = frameAt(slot);
    if (frame == null) return null;
    if (frame.pageId != 0) {
      StatusCode status = io.writeStaged(frame);
      if (!status.isOk()) return fail(status);
      map.remove(frame.pageId);
    }
    long pageGeneration = cache.nextPageGeneration();
    if (pageGeneration == 0) return null;
    frame.pageId = pageId;
    frame.beginPageGeneration(pageGeneration);
    IndexedPageFrame preparedFrame = preparedFrame(pageId);
    frame.identity(
        preparedFrame == null ? state.payloadKind(pageId) : preparedFrame.payloadKind,
        preparedFrame == null ? state.ownerKeyId(pageId) : preparedFrame.ownerKeyId);
    frame.dirty = false;
    frame.access = ++accessClock;
    map.put(pageId, slot);
    return frame;
  }

  IndexedPageFrame frame(int pageId) {
    int slot = map.find(pageId);
    if (slot < 0) {
      if (!state.staged(pageId)) return null;
      IndexedPageFrame frame = acquire(pageId);
      if (frame == null) return null;
      StatusCode status = io.loadStaged(frame);
      frame.identity(state.payloadKind(pageId), state.ownerKeyId(pageId));
      if (status.isOk()) return frame;
      release(pageId);
      cache.setStatus(status);
      return null;
    }
    IndexedPageFrame frame = frames[slot];
    frame.access = ++accessClock;
    return frame;
  }

  void release(int pageId) {
    int slot = map.find(pageId);
    if (slot < 0) return;
    map.remove(pageId);
    frames[slot].pageId = 0;
    frames[slot].invalidatePageValidation();
  }

  void discardVacuumPages() {
    for (IndexedPageFrame frame : frames) {
      if (frame == null || frame.pageId == 0) continue;
      map.remove(frame.pageId);
      frame.pageId = 0;
      frame.invalidatePageValidation();
    }
  }

  private IndexedPageFrame preparedFrame(int pageId) {
    IndexedPageFrame frame = prepared.frame(pageId, current.frames());
    if (frame != null) frame.access = ++accessClock;
    return frame;
  }

  IndexedPageFrame frameAt(int slot) {
    IndexedPageFrame frame = frames[slot];
    if (frame != null) return frame;
    try {
      frame = new IndexedPageFrame();
      frames[slot] = frame;
      return frame;
    } catch (OutOfMemoryError error) {
      return fail(StatusCode.RESOURCE_EXHAUSTED);
    }
  }

  private IndexedPageFrame fail(StatusCode status) {
    cache.setStatus(status);
    return null;
  }
}
