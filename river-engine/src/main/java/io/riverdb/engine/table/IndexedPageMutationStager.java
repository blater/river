package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.page.PageCodec;
import java.nio.ByteBuffer;

/** Owns operation-page admission, staging copies, identity transitions, and rollback. */
final class IndexedPageMutationStager {
  private final IndexedPageFrameCache cache;
  private final IndexedPageState state;
  private final IndexedStagingPageFrameStore staging;
  private final IndexedPageMutationMaterializer materializer;
  private final IndexedPageStagingAdmission admission;

  IndexedPageMutationStager(
      IndexedPageFrameCache owner, IndexedPageState pageState, IndexedPageFrameIo frameIo,
      IndexedCurrentPageFrameStore currentStore, IndexedStagingPageFrameStore stagingStore,
      IndexedPreparedPageBatch preparedBatch) {
    cache = owner;
    state = pageState;
    staging = stagingStore;
    materializer = new IndexedPageMutationMaterializer(
        owner, pageState, frameIo, currentStore, stagingStore, preparedBatch);
    admission = new IndexedPageStagingAdmission(
        owner, pageState, preparedBatch, stagingStore);
  }

  ByteBuffer stageExisting(int pageId, int maximumChangedPages) {
    if (!IndexedPageFrameCache.validPageId(pageId)) return null;
    int existingSlot = staging.map().find(pageId);
    if (existingSlot >= 0) return staging.frames()[existingSlot].payload;
    boolean alreadyStaged = state.staged(pageId);
    if (!admission.admitExisting(pageId, maximumChangedPages, alreadyStaged)) return null;
    IndexedPageFrame frame = staging.acquire(pageId);
    if (frame == null) {
      admission.markCapacityPressure(cache.lastStatus());
      admission.rollback(pageId, alreadyStaged);
      return null;
    }
    StatusCode status = materializer.populateExisting(pageId, frame, alreadyStaged);
    if (!status.isOk()) {
      staging.release(pageId);
      admission.markCapacityPressure(status);
      admission.rollback(pageId, alreadyStaged);
      return null;
    }
    state.addCopyBytes(PageCodec.PAGE_BYTES);
    cache.setStatus(StatusCode.OK);
    return frame.payload;
  }

  ByteBuffer stageNew(int pageId, int maximumChangedPages, int payloadKind, long ownerKeyId) {
    boolean recycled = state.present(pageId)
        && materializer.payloadKind(pageId) == PageCodec.PAYLOAD_KIND_FREE
        && materializer.ownerKeyId(pageId) == PageCodec.SCALAR_OWNER_KEY_ID;
    if (!IndexedPageFrameCache.validPageId(pageId)
        || state.present(pageId) && !recycled
        || !IndexedPageIdentity.valid(payloadKind, ownerKeyId)) {
      cache.setStatus(StatusCode.INVALID_EXTERNAL_INPUT);
      return null;
    }
    int existingSlot = staging.map().find(pageId);
    if (existingSlot >= 0) {
      IndexedPageFrame existing = staging.frames()[existingSlot];
      if (existing.payloadKind != payloadKind || existing.ownerKeyId != ownerKeyId) {
        cache.setStatus(StatusCode.CORRUPTION);
        return null;
      }
      return existing.payload;
    }
    boolean alreadyStaged = state.staged(pageId);
    if (!materializer.matchingIdentity(pageId, payloadKind, ownerKeyId, alreadyStaged)) {
      cache.setStatus(StatusCode.CORRUPTION);
      return null;
    }
    if (!admission.admitNew(pageId, maximumChangedPages, alreadyStaged)) return null;
    IndexedPageFrame frame = staging.acquire(pageId);
    if (frame == null) {
      admission.markCapacityPressure(cache.lastStatus());
      admission.rollback(pageId, alreadyStaged);
      return null;
    }
    return materializer.prepareNew(pageId, payloadKind, ownerKeyId, frame, alreadyStaged);
  }

  ByteBuffer stageFreeTuple(int pageId, long ownerKeyId, int maximumChangedPages) {
    if (!materializer.identityMatches(pageId, PageCodec.PAYLOAD_KIND_TUPLE_BTREE, ownerKeyId)) {
      cache.setStatus(StatusCode.CORRUPTION);
      return null;
    }
    ByteBuffer payload = stageExisting(pageId, maximumChangedPages);
    IndexedPageFrame frame = staging.frame(pageId);
    if (payload == null || frame == null) return null;
    frame.rememberIdentity(PageCodec.PAYLOAD_KIND_TUPLE_BTREE, ownerKeyId);
    frame.invalidatePageValidation();
    for (int index = 0; index < PageCodec.PAGE_BYTES; index++) {
      frame.page.put(index, (byte) 0);
    }
    frame.payload.clear();
    frame.identity(PageCodec.PAYLOAD_KIND_FREE, PageCodec.SCALAR_OWNER_KEY_ID);
    state.setIdentity(pageId, PageCodec.PAYLOAD_KIND_FREE, PageCodec.SCALAR_OWNER_KEY_ID);
    cache.setStatus(StatusCode.OK);
    return payload;
  }

  void clearStagedFlags() { admission.clearStagedFlags(); }

  StatusCode beginMemberAdmission(IndexedPreparedLogicalCommit member) {
    return admission.beginMember(member);
  }

  boolean memberCapacityPressure() { return admission.capacityPressure(); }
  void endMemberAdmission() { admission.endMember(); }
  boolean identityMatches(int pageId, int payloadKind, long ownerKeyId) {
    return materializer.identityMatches(pageId, payloadKind, ownerKeyId);
  }

  void rollbackStagedMember() {
    admission.rollbackMember();
  }

  void markCapacityPressure(StatusCode status) { admission.markCapacityPressure(status); }

}
