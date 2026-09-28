package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.page.PageCodec;
import java.nio.ByteBuffer;

/** Owns vacuum shadow pages and their durable publication sequence. */
final class IndexedPageVacuum {
  private final IndexedPageFrameCache cache;
  private final IndexedPageState state;
  private final IndexedPageFrameIo io;
  private final IndexedCurrentPageFrameStore current;
  private final IndexedStagingPageFrameStore staging;

  IndexedPageVacuum(
      IndexedPageFrameCache owner, IndexedPageState pageState, IndexedPageFrameIo frameIo,
      IndexedCurrentPageFrameStore currentStore, IndexedStagingPageFrameStore stagingStore) {
    cache = owner;
    state = pageState;
    io = frameIo;
    current = currentStore;
    staging = stagingStore;
  }

  ByteBuffer beginPage(int pageId) {
    if (!validPage(pageId)) {
      cache.setStatus(StatusCode.CORRUPTION);
      return null;
    }
    IndexedPageFrame source = current.currentFrame(pageId, true);
    if (source == null) return null;
    IndexedPageFrame shadow = staging.acquire(pageId);
    if (shadow == null) return null;
    IndexedPageFrameCodec.copyPage(source.page, shadow.page);
    shadow.identity(source.payloadKind, source.ownerKeyId);
    state.addCopyBytes(PageCodec.PAGE_BYTES);
    cache.setStatus(StatusCode.OK);
    return shadow.payload;
  }

  ByteBuffer payload(int pageId) {
    if (!validPage(pageId)) {
      cache.setStatus(StatusCode.CORRUPTION);
      return null;
    }
    int slot = staging.map().find(pageId);
    if (slot >= 0) return staging.frames()[slot].payload;
    IndexedPageFrame source = current.currentFrame(pageId, true);
    if (source == null) return null;
    IndexedPageFrame shadow = staging.acquire(pageId);
    if (shadow == null) return null;
    StatusCode status = io.loadStaged(shadow);
    if (!status.isOk()) {
      staging.release(pageId);
      cache.setStatus(status);
      return null;
    }
    shadow.identity(source.payloadKind, source.ownerKeyId);
    cache.setStatus(StatusCode.OK);
    return shadow.payload;
  }

  StatusCode sealPage(int pageId) {
    IndexedPageFrame shadow = staging.frame(pageId);
    if (shadow == null) return cache.setStatus(StatusCode.CORRUPTION);
    StatusCode status = io.writeStaged(shadow);
    if (status.isOk()) staging.release(pageId);
    return cache.setStatus(status);
  }

  StatusCode publishPage(int pageId, long start, long end) {
    ByteBuffer shadowPayload = payload(pageId);
    if (shadowPayload == null) return cache.lastStatus();
    IndexedPageFrame shadow = staging.frame(pageId);
    IndexedPageFrame target = current.currentFrame(pageId, true);
    if (shadow == null || target == null) return cache.lastStatus();
    long pageGeneration = cache.nextPageGeneration();
    if (pageGeneration == 0) return cache.lastStatus();
    target.beginPageGeneration(pageGeneration);
    target.copyPageFrom(shadow);
    target.identity(shadow.payloadKind, shadow.ownerKeyId);
    StatusCode status = state.markChanged(pageId, start, end);
    if (status.isOk()) {
      target.dirty = true;
      target.recordStart = start;
      target.recordEnd = end;
      status = io.writeBack(target);
    }
    if (status.isOk()) staging.release(pageId);
    return cache.setStatus(status);
  }

  StatusCode forcePublication() { return cache.setStatus(io.forceBacking()); }

  void discardPages() {
    staging.discardVacuumPages();
    cache.setStatus(StatusCode.OK);
  }

  private boolean validPage(int pageId) {
    return IndexedPageFrameCache.validPageId(pageId)
        && state.present(pageId) && !state.staged(pageId);
  }
}
