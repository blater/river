package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;

/** Owns exact frame identity for allocation-free operation-page borrows. */
final class IndexedOperationPagePins {
  private final IndexedPageFrameCache cache;

  IndexedOperationPagePins(IndexedPageFrameCache frameCache) {
    cache = frameCache;
  }

  StatusCode pin(
      int pageId, boolean writable, int maximumChangedPages,
      IndexedOperationPage result) {
    if (result == null || result.attached() || !IndexedPageFrameCache.validPageId(pageId)) {
      return cache.setStatus(StatusCode.INVALID_EXTERNAL_INPUT);
    }
    if (writable && cache.stageExisting(pageId, maximumChangedPages) == null) {
      return cache.lastStatus();
    }
    int arena = arena(pageId);
    IndexedPageFrame frame = frame(pageId, arena);
    int slot = slot(pageId, arena);
    if (frame == null || slot < 0) return cache.setStatus(StatusCode.INVARIANT_BROKEN);
    if (writable && !frame.beginWritableBorrow()) {
      return cache.setStatus(StatusCode.INVARIANT_BROKEN);
    }
    frame.pinCount++;
    result.set(
        pageId, frame.payload, arena, slot, frame.pageGeneration, writable);
    return cache.setStatus(StatusCode.OK);
  }

  StatusCode pinNew(
      int pageId, int maximumChangedPages, int payloadKind,
      long ownerKeyId, IndexedOperationPage result) {
    if (result == null || result.attached() || !IndexedPageFrameCache.validPageId(pageId)
        || !IndexedPageIdentity.valid(payloadKind, ownerKeyId)) {
      return cache.setStatus(StatusCode.INVALID_EXTERNAL_INPUT);
    }
    if (cache.stageNew(
        pageId, maximumChangedPages, payloadKind, ownerKeyId) == null) {
      return cache.lastStatus();
    }
    int slot = cache.stagingMap.find(pageId);
    IndexedPageFrame frame = cache.stagingFrame(pageId);
    if (frame == null || slot < 0) return cache.setStatus(StatusCode.INVARIANT_BROKEN);
    if (!frame.beginWritableBorrow()) {
      return cache.setStatus(StatusCode.INVARIANT_BROKEN);
    }
    frame.pinCount++;
    result.set(
        pageId, frame.payload, IndexedOperationPage.STAGING_ARENA,
        slot, frame.pageGeneration, true);
    return cache.setStatus(StatusCode.OK);
  }

  StatusCode release(IndexedOperationPage page) {
    if (page == null || !page.attached()) return StatusCode.INVALID_EXTERNAL_INPUT;
    IndexedPageFrame[] frames = page.arena() == IndexedOperationPage.STAGING_ARENA
        ? cache.stagingFrames : cache.currentFrames;
    int slot = page.frameSlot();
    if (!validArena(page.arena()) || slot < 0 || slot >= frames.length) {
      return StatusCode.INVARIANT_BROKEN;
    }
    IndexedPageFrame frame = frames[slot];
    int ownedPins = page.arena() == IndexedOperationPage.PREPARED_ARENA ? 1 : 0;
    if (frame == null || frame.pageId != page.pageId()
        || frame.pageGeneration != page.pageGeneration()
        || frame.payload != page.payload() || frame.pinCount <= ownedPins) {
      return StatusCode.INVARIANT_BROKEN;
    }
    if (page.writable()) {
      StatusCode status = frame.endWritableBorrow();
      if (!status.isOk()) return cache.setStatus(status);
    }
    frame.pinCount--;
    page.reset();
    return StatusCode.OK;
  }

  private int arena(int pageId) {
    if (cache.state.staged(pageId)) return IndexedOperationPage.STAGING_ARENA;
    return cache.prepared.contains(pageId)
        ? IndexedOperationPage.PREPARED_ARENA : IndexedOperationPage.CURRENT_ARENA;
  }

  private int slot(int pageId, int arena) {
    if (arena == IndexedOperationPage.STAGING_ARENA) return cache.stagingMap.find(pageId);
    return arena == IndexedOperationPage.PREPARED_ARENA
        ? cache.prepared.slot(pageId) : cache.currentMap.find(pageId);
  }

  private IndexedPageFrame frame(int pageId, int arena) {
    if (arena == IndexedOperationPage.STAGING_ARENA) return cache.stagingFrame(pageId);
    if (arena == IndexedOperationPage.PREPARED_ARENA) return cache.preparedFrame(pageId);
    if (!cache.state.present(pageId)) {
      cache.setStatus(StatusCode.CORRUPTION);
      return null;
    }
    return cache.currentFrame(pageId, true);
  }

  private static boolean validArena(int arena) {
    return arena == IndexedOperationPage.CURRENT_ARENA
        || arena == IndexedOperationPage.STAGING_ARENA
        || arena == IndexedOperationPage.PREPARED_ARENA;
  }
}
