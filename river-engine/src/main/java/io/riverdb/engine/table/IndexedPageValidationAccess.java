package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.btree.TupleBTreePageValidationProof;

/** Owns generation-scoped validation proof access for current and staged frames. */
final class IndexedPageValidationAccess {
  private final IndexedPageFrameCache cache;
  private final IndexedCurrentPageFrameStore current;
  private final IndexedStagingPageFrameStore staging;
  private final IndexedPreparedPageBatch prepared;

  IndexedPageValidationAccess(
      IndexedPageFrameCache owner, IndexedCurrentPageFrameStore currentStore,
      IndexedStagingPageFrameStore stagingStore, IndexedPreparedPageBatch preparedBatch) {
    cache = owner;
    current = currentStore;
    staging = stagingStore;
    prepared = preparedBatch;
  }

  StatusCode restore(
      int pageId, long pageGeneration, long schemaId,
      long descriptorHash, int expectedType, TupleBTreePageValidationProof target) {
    IndexedPageFrame frame = frameForGeneration(pageId, pageGeneration);
    return frame == null ? StatusCode.CONFLICT : frame.restorePageValidation(
        pageGeneration, schemaId, descriptorHash, expectedType, target);
  }

  StatusCode remember(
      int pageId, long pageGeneration, long schemaId,
      long descriptorHash, int pageType, TupleBTreePageValidationProof source) {
    IndexedPageFrame frame = frameForGeneration(pageId, pageGeneration);
    return frame == null ? StatusCode.CONFLICT
        : frame.rememberPageValidation(schemaId, descriptorHash, pageType, source);
  }

  StatusCode consumeTupleMutationInput(
      int pageId, long pageGeneration, long ownerKeyId,
      long schemaId, long descriptorHash, int pageType,
      TupleBTreePageValidationProof target) {
    IndexedPageFrame frame = cache.stagingFrame(pageId);
    return frame != null && frame.payloadKind == io.riverdb.format.page.PageCodec.PAYLOAD_KIND_TUPLE_BTREE
        && frame.ownerKeyId == ownerKeyId
        ? frame.consumeMutationInputValidation(
            pageGeneration, schemaId, descriptorHash, pageType, target)
        : StatusCode.CONFLICT;
  }

  StatusCode sealTupleMutation(
      int pageId, long pageGeneration, long ownerKeyId,
      long schemaId, long descriptorHash, int pageType,
      TupleBTreePageValidationProof source) {
    IndexedPageFrame frame = cache.stagingFrame(pageId);
    return frame != null && frame.payloadKind == io.riverdb.format.page.PageCodec.PAYLOAD_KIND_TUPLE_BTREE
        && frame.ownerKeyId == ownerKeyId
        ? frame.sealMutationValidation(
            pageGeneration, schemaId, descriptorHash, pageType, source)
        : StatusCode.INVARIANT_BROKEN;
  }

  private IndexedPageFrame frameForGeneration(int pageId, long pageGeneration) {
    if (!IndexedPageFrameCache.validPageId(pageId) || pageGeneration <= 0) return null;
    int stagingSlot = staging.map().find(pageId);
    IndexedPageFrame frame = stagingSlot < 0 ? null : staging.frames()[stagingSlot];
    if (frame != null && frame.pageGeneration == pageGeneration) return frame;
    frame = prepared.frame(pageId, current.frames());
    if (frame != null && frame.pageGeneration == pageGeneration) return frame;
    return current.frameForGeneration(pageId, pageGeneration);
  }
}
