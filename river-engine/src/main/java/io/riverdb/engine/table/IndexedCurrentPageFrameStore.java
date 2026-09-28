package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.page.PageCodec;
import io.riverdb.platform.file.IoResult;
import java.nio.ByteBuffer;

/** Owns current-page residency, version links, pin-aware reuse, and frame loading. */
final class IndexedCurrentPageFrameStore {
  private static final IndexedPageFrame[] DETACHED = new IndexedPageFrame[0];

  private final IndexedPageFrameCache cache;
  private final IndexedPageState state;
  private final IndexedPageFrameIo io;
  private final IoResult readResult = new IoResult();
  private IndexedPageFrame[] frames;
  private IndexedPageFrameMap map;
  private final IndexedCurrentFrameReuse reuse;
  private long accessClock;

  IndexedCurrentPageFrameStore(
      IndexedPageFrameCache owner, IndexedPageState pageState,
      IndexedPageFrameIo frameIo, int frameCount, int mapCapacity) {
    cache = owner;
    state = pageState;
    io = frameIo;
    frames = new IndexedPageFrame[frameCount];
    map = new IndexedPageFrameMap(mapCapacity);
    reuse = new IndexedCurrentFrameReuse(owner, pageState, frameIo, frames, map);
  }

  IndexedPageFrame[] frames() { return frames; }
  IndexedPageFrameMap map() { return map; }

  void touch(IndexedPageFrame frame) { frame.access = ++accessClock; }

  void detach() {
    for (IndexedPageFrame frame : frames) {
      if (frame != null) frame.invalidatePageValidation();
    }
    map.detach();
    frames = DETACHED;
    reuse.detach();
  }

  void abandon() { detach(); }

  ByteBuffer payloadUnchecked(int pageId) {
    IndexedPageFrame frame = currentFrame(pageId, true);
    return frame == null ? null : frame.payload;
  }

  IndexedPageFrame currentFrame(int pageId, boolean load) {
    int slot = map.find(pageId);
    if (slot >= 0) {
      IndexedPageFrame frame = frames[slot];
      frame.access = ++accessClock;
      return frame;
    }
    return load && state.present(pageId) ? loadCurrentFrame(pageId) : null;
  }

  IndexedPageFrame currentFrameForRead(int pageId) {
    IndexedPageFrame frame = currentFrame(pageId, false);
    return frame == null ? acquire(pageId, true) : frame;
  }

  private IndexedPageFrame loadCurrentFrame(int pageId) {
    IndexedPageFrame frame = acquire(pageId, true);
    if (frame == null) return null;
    StatusCode status = io.readCurrent(frame, readResult);
    if (!status.isOk()) {
      release(pageId);
      cache.setStatus(status);
      return null;
    }
    return frame;
  }

  IndexedPageFrame acquire(int pageId, boolean allowEviction) {
    int existing = map.find(pageId);
    if (existing >= 0) return frames[existing];
    int slot = reuse.reusable(allowEviction, Long.MIN_VALUE);
    if (slot < 0) return fail(StatusCode.RESOURCE_EXHAUSTED);
    IndexedPageFrame frame = reuse.frameAt(slot);
    if (frame == null) return null;
    if (frame.pageId != 0) {
      StatusCode status = reuse.prepare(slot);
      if (!status.isOk()) return fail(status);
    }
    long pageGeneration = cache.nextPageGeneration();
    if (pageGeneration == 0) return null;
    frame.pageId = pageId;
    frame.beginPageGeneration(pageGeneration);
    frame.identity(PageCodec.PAYLOAD_KIND_SCALAR_BTREE, PageCodec.SCALAR_OWNER_KEY_ID);
    frame.recordStart = 0;
    frame.recordEnd = 0;
    frame.dirty = false;
    frame.clearGeneration();
    frame.access = ++accessClock;
    map.put(pageId, slot);
    return frame;
  }

  int reusableCurrentSlot(boolean allowEviction, long oldestVisibleCommitSequence) {
    return reuse.reusable(allowEviction, oldestVisibleCommitSequence);
  }

  StatusCode prepareForReuse(int slot) {
    return reuse.prepare(slot);
  }

  StatusCode reclaimHistorical(long oldestVisibleCommitSequence) {
    return reuse.reclaim(oldestVisibleCommitSequence);
  }

  void unpin(int pageId) {
    int slot = map.find(pageId);
    if (slot < 0) return;
    IndexedPageFrame frame = frames[slot];
    if (frame.pinCount > 0) frame.pinCount--;
  }

  void release(int pageId) {
    int slot = map.find(pageId);
    if (slot < 0) return;
    map.remove(pageId);
    frames[slot].pageId = 0;
    frames[slot].clearGeneration();
  }

  IndexedPageFrame frameForGeneration(int pageId, long pageGeneration) {
    int slot = map.find(pageId);
    while (slot >= 0) {
      IndexedPageFrame frame = frames[slot];
      if (frame == null) return null;
      if (frame.pageGeneration == pageGeneration) return frame;
      slot = frame.previousVersionSlot;
    }
    return null;
  }

  IndexedPageFrame frameAt(int slot) { return reuse.frameAt(slot); }

  private IndexedPageFrame fail(StatusCode status) {
    cache.setStatus(status);
    return null;
  }
}
