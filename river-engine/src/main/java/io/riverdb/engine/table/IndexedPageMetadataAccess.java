package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.page.PageCodec;
import java.nio.ByteBuffer;

/** Owns page-state projections and current-frame dirty metadata transitions. */
final class IndexedPageMetadataAccess {
  private final IndexedPageFrameCache cache;
  private final IndexedPageState state;
  private final IndexedCurrentPageFrameStore current;
  private final IndexedStagingPageFrameStore staging;
  private final IndexedPreparedPageBatch prepared;

  IndexedPageMetadataAccess(
      IndexedPageFrameCache owner, IndexedPageState pageState,
      IndexedCurrentPageFrameStore currentStore, IndexedStagingPageFrameStore stagingStore,
      IndexedPreparedPageBatch preparedBatch) {
    cache = owner;
    state = pageState;
    current = currentStore;
    staging = stagingStore;
    prepared = preparedBatch;
  }

  ByteBuffer currentPayloadUnchecked(int pageId) {
    return current.payloadUnchecked(pageId);
  }

  ByteBuffer currentPayload(int pageId) {
    return state.present(pageId) ? currentPayloadUnchecked(pageId) : null;
  }

  ByteBuffer operationPayload(int pageId) {
    if (!IndexedPageFrameCache.validPageId(pageId)) return null;
    IndexedPageFrame frame = staging.frame(pageId);
    if (frame != null) return frame.payload;
    frame = preparedFrame(pageId);
    if (frame != null) return frame.payload;
    return state.present(pageId) ? currentPayloadUnchecked(pageId) : null;
  }

  StatusCode markCurrentChanged(int pageId, long start, long end) {
    IndexedPageFrame frame = current.currentFrame(pageId, false);
    if (frame == null) return cache.setStatus(StatusCode.RESOURCE_EXHAUSTED);
    StatusCode status = state.markChanged(pageId, start, end);
    if (!status.isOk()) return cache.setStatus(status);
    frame.dirty = true;
    frame.recordStart = start;
    frame.recordEnd = end;
    return cache.setStatus(StatusCode.OK);
  }

  StatusCode reidentifyCurrent(int pageId, int payloadKind, long ownerKeyId) {
    if (!state.present(pageId) || payloadKind(pageId) != PageCodec.PAYLOAD_KIND_FREE
        || !IndexedPageIdentity.valid(payloadKind, ownerKeyId)) {
      return cache.setStatus(StatusCode.CORRUPTION);
    }
    IndexedPageFrame frame = current.currentFrame(pageId, true);
    if (frame == null) return cache.lastStatus();
    frame.identity(payloadKind, ownerKeyId);
    return cache.setStatus(StatusCode.OK);
  }

  void markClean(int pageId) { clearDirty(pageId, true); }
  void markRebased(int pageId) { clearDirty(pageId, false); }

  boolean validPresentPage(int pageId) { return state.present(pageId); }

  boolean operationPresentPage(int pageId) {
    return state.present(pageId) || prepared.contains(pageId);
  }

  boolean hasDirtyPages() { return state.hasDirtyPages(); }

  boolean addChangedPage(int pageId, int maximum) {
    StatusCode status = state.addChangedPage(pageId, maximum);
    cache.setStatus(status);
    return status.isOk();
  }

  int changedPageCount() { return state.changedPageCount(); }
  int changedPageCapacity() { return state.changedPageCapacity(); }
  int changedPageId(int index) { return state.changedPageId(index); }
  int highestPageId() { return state.highestPageId(); }
  long stagedCopyBytes() { return state.stagedCopyBytes(); }

  long recordStart(int pageId) {
    if (state.dirty(pageId)) return state.recordStart(pageId);
    IndexedPageFrame frame = current.currentFrame(pageId, true);
    return frame == null ? 0 : frame.recordStart;
  }

  long recordEnd(int pageId) {
    if (state.dirty(pageId)) return state.recordEnd(pageId);
    IndexedPageFrame frame = current.currentFrame(pageId, true);
    return frame == null ? 0 : frame.recordEnd;
  }

  int payloadKind(int pageId) {
    if (state.staged(pageId)) return state.payloadKind(pageId);
    IndexedPageFrame frame = preparedFrame(pageId);
    if (frame != null) return frame.payloadKind;
    int slot = current.map().find(pageId);
    if (slot >= 0) return current.frames()[slot].payloadKind;
    if (!state.present(pageId)) return PageCodec.PAYLOAD_KIND_SCALAR_BTREE;
    frame = current.currentFrame(pageId, true);
    return frame == null ? PageCodec.PAYLOAD_KIND_SCALAR_BTREE : frame.payloadKind;
  }

  long ownerKeyId(int pageId) {
    if (state.staged(pageId)) return state.ownerKeyId(pageId);
    IndexedPageFrame frame = preparedFrame(pageId);
    if (frame != null) return frame.ownerKeyId;
    int slot = current.map().find(pageId);
    if (slot >= 0) return current.frames()[slot].ownerKeyId;
    if (!state.present(pageId)) return PageCodec.SCALAR_OWNER_KEY_ID;
    frame = current.currentFrame(pageId, true);
    return frame == null ? PageCodec.SCALAR_OWNER_KEY_ID : frame.ownerKeyId;
  }

  private void clearDirty(int pageId, boolean clean) {
    if (clean) state.markClean(pageId);
    else state.markRebased(pageId);
    IndexedPageFrame frame = current.currentFrame(pageId, false);
    if (frame == null) return;
    frame.dirty = false;
    frame.recordStart = 0;
    frame.recordEnd = 0;
  }

  private IndexedPageFrame preparedFrame(int pageId) {
    IndexedPageFrame frame = prepared.frame(pageId, current.frames());
    if (frame != null) current.touch(frame);
    return frame;
  }
}
