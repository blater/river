package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.page.PageCodec;
import java.nio.ByteBuffer;

/** Resolves page identity and materializes current or prepared contents into staging frames. */
final class IndexedPageMutationMaterializer {
  private final IndexedPageFrameCache cache;
  private final IndexedPageState state;
  private final IndexedPageFrameIo io;
  private final IndexedCurrentPageFrameStore current;
  private final IndexedStagingPageFrameStore staging;
  private final IndexedPreparedPageBatch prepared;

  IndexedPageMutationMaterializer(
      IndexedPageFrameCache owner, IndexedPageState pageState, IndexedPageFrameIo frameIo,
      IndexedCurrentPageFrameStore currentStore, IndexedStagingPageFrameStore stagingStore,
      IndexedPreparedPageBatch preparedBatch) {
    cache = owner;
    state = pageState;
    io = frameIo;
    current = currentStore;
    staging = stagingStore;
    prepared = preparedBatch;
  }

  StatusCode populateExisting(
      int pageId, IndexedPageFrame target, boolean alreadyStaged) {
    if (alreadyStaged) return io.loadStaged(target);
    IndexedPageFrame source = preparedFrame(pageId);
    if (source == null) source = current.currentFrame(pageId, true);
    if (source == null) return cache.lastStatus();
    target.copyPageFrom(source);
    target.identity(source.payloadKind, source.ownerKeyId);
    target.rememberIdentity(source.payloadKind, source.ownerKeyId);
    return state.setIdentity(pageId, source.payloadKind, source.ownerKeyId);
  }

  ByteBuffer prepareNew(
      int pageId, int payloadKind, long ownerKeyId,
      IndexedPageFrame target, boolean alreadyStaged) {
    if (alreadyStaged) return loadNew(pageId, target);
    IndexedPageFrame source = state.present(pageId) ? current.currentFrame(pageId, true) : null;
    if (state.present(pageId) && source == null) {
      staging.release(pageId);
      return null;
    }
    target.rememberIdentity(
        source == null ? PageCodec.PAYLOAD_KIND_SCALAR_BTREE : source.payloadKind,
        source == null ? PageCodec.SCALAR_OWNER_KEY_ID : source.ownerKeyId);
    target.invalidatePageValidation();
    clear(target.page);
    target.identity(payloadKind, ownerKeyId);
    StatusCode identity = state.setIdentity(pageId, payloadKind, ownerKeyId);
    if (!identity.isOk()) {
      staging.release(pageId);
      cache.setStatus(identity);
      return null;
    }
    cache.setStatus(StatusCode.OK);
    return target.payload;
  }

  boolean matchingIdentity(
      int pageId, int payloadKind, long ownerKeyId, boolean alreadyStaged) {
    return !alreadyStaged || state.payloadKind(pageId) == payloadKind
        && state.ownerKeyId(pageId) == ownerKeyId;
  }

  boolean identityMatches(int pageId, int payloadKind, long ownerKeyId) {
    if (!IndexedPageFrameCache.validPageId(pageId)) return false;
    if (state.staged(pageId)) {
      return state.payloadKind(pageId) == payloadKind
          && state.ownerKeyId(pageId) == ownerKeyId;
    }
    IndexedPageFrame preparedFrame = preparedFrame(pageId);
    if (preparedFrame != null) {
      return preparedFrame.payloadKind == payloadKind
          && preparedFrame.ownerKeyId == ownerKeyId;
    }
    if (!state.present(pageId)) return false;
    IndexedPageFrame frame = current.currentFrame(pageId, true);
    return frame != null && frame.payloadKind == payloadKind && frame.ownerKeyId == ownerKeyId;
  }

  int payloadKind(int pageId) {
    IndexedPageFrame frame = preparedFrame(pageId);
    return frame == null ? state.payloadKind(pageId) : frame.payloadKind;
  }

  long ownerKeyId(int pageId) {
    IndexedPageFrame frame = preparedFrame(pageId);
    return frame == null ? state.ownerKeyId(pageId) : frame.ownerKeyId;
  }

  private ByteBuffer loadNew(int pageId, IndexedPageFrame target) {
    StatusCode status = io.loadStaged(target);
    target.identity(state.payloadKind(pageId), state.ownerKeyId(pageId));
    if (status.isOk()) {
      cache.setStatus(StatusCode.OK);
      return target.payload;
    }
    staging.release(pageId);
    cache.setStatus(status);
    return null;
  }

  private IndexedPageFrame preparedFrame(int pageId) {
    IndexedPageFrame frame = prepared.frame(pageId, current.frames());
    if (frame != null) current.touch(frame);
    return frame;
  }

  private static void clear(ByteBuffer page) {
    for (int index = 0; index < PageCodec.PAGE_BYTES; index++) {
      page.put(index, (byte) 0);
    }
    page.position(0);
    page.limit(PageCodec.PAGE_BYTES);
  }
}
