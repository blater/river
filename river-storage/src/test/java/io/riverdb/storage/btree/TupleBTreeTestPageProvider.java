package io.riverdb.storage.btree;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.btree.TupleBTreePageValidationProof;
import io.riverdb.format.page.PageCodec;
import java.nio.ByteBuffer;
import java.util.IdentityHashMap;

/** Fixed-capacity in-memory provider used to verify the storage ownership contract. */
final class TupleBTreeTestPageProvider implements TupleBTreePageProvider {
  private final ByteBuffer[] pages;
  private final long[] generations;
  private final TupleBTreeTestPageValidationState validation;
  private final IdentityHashMap<TupleBTreePageReference, Boolean> pinned =
      new IdentityHashMap<>();
  private int pageCount;
  private int rootPageId;
  private long rootGeneration;
  private int releasesBeforeFailure = -1;

  TupleBTreeTestPageProvider(int maximumPages) {
    pages = new ByteBuffer[maximumPages + 1];
    generations = new long[maximumPages + 1];
    validation = new TupleBTreeTestPageValidationState(maximumPages);
  }

  @Override
  public int rootPageId() {
    return rootPageId;
  }

  @Override
  public long rootGeneration() { return rootGeneration; }

  @Override
  public StatusCode pin(int pageId, boolean writable, TupleBTreePageReference result) {
    if (result == null || result.isAttached() || pageId <= 0 || pageId > pageCount
        || pages[pageId] == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (writable && validation.writableBorrowed(pageId)) {
      return StatusCode.INVARIANT_BROKEN;
    }
    StatusCode status = result.attach(
        pageId, pages[pageId], 0, writable, generations[pageId]);
    if (status.isOk() && writable) {
      validation.beginWritableBorrow(pageId, generations[pageId], pages[pageId]);
    }
    if (status.isOk()) pinned.put(result, Boolean.TRUE);
    return status;
  }

  @Override
  public StatusCode allocate(TupleBTreePageReference result) {
    if (result == null || result.isAttached()) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (pageCount >= pages.length - 1) return StatusCode.RESOURCE_EXHAUSTED;
    int pageId = ++pageCount;
    pages[pageId] = ByteBuffer.allocate(PageCodec.MAX_PAYLOAD_BYTES);
    generations[pageId] = 1;
    validation.invalidate(pageId);
    StatusCode status = result.attach(pageId, pages[pageId], 0, true, generations[pageId]);
    if (status.isOk()) {
      validation.beginWritableBorrow(pageId, generations[pageId], pages[pageId]);
      pinned.put(result, Boolean.TRUE);
    }
    return status;
  }

  @Override
  public StatusCode restorePageValidation(
      TupleBTreePageReference reference, long schemaId,
      long descriptorHash, int expectedType,
      TupleBTreePageValidationProof target) {
    if (!owns(reference)) return StatusCode.INVALID_EXTERNAL_INPUT;
    int pageId = reference.pageId();
    if (validation.writableBorrowed(pageId)) {
      return validation.restoreMiss(target);
    }
    return validation.restore(
        pageId, generations[pageId], pages[pageId],
        schemaId, descriptorHash, expectedType, target);
  }

  @Override
  public StatusCode rememberPageValidation(
      TupleBTreePageReference reference, long schemaId,
      long descriptorHash, int pageType,
      TupleBTreePageValidationProof source) {
    if (!owns(reference)) return StatusCode.INVALID_EXTERNAL_INPUT;
    int pageId = reference.pageId();
    if (pageId <= 0 || pageId >= pages.length
        || reference.pageGeneration() != generations[pageId]
        || source == null || !source.matches(
            pages[pageId], 0, schemaId, descriptorHash, pageType)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (validation.writableBorrowed(pageId)) return StatusCode.INVALID_EXTERNAL_INPUT;
    return validation.remember(
        pageId, generations[pageId], pages[pageId], source);
  }

  @Override
  public StatusCode consumeCanonicalMutationValidation(
      TupleBTreePageReference reference, long schemaId,
      long descriptorHash, int pageType,
      TupleBTreePageValidationProof target) {
    if (reference == null || !reference.isAttached() || !reference.isWritable()
        || !pinned.containsKey(reference)) return StatusCode.INVALID_EXTERNAL_INPUT;
    int pageId = reference.pageId();
    if (pageId <= 0 || pageId >= pages.length) {
      if (target != null) target.reset();
      return StatusCode.CONFLICT;
    }
    if (!validation.writableBorrowed(pageId)
        || reference.page() != pages[pageId]
        || reference.pageGeneration() != generations[pageId]) {
      return validation.rejectCanonicalMutation(pageId, target);
    }
    return validation.consumeCanonicalMutation(
        pageId, generations[pageId], pages[pageId],
        schemaId, descriptorHash, pageType, target);
  }

  @Override
  public StatusCode sealCanonicalMutation(
      TupleBTreePageReference reference, long schemaId,
      long descriptorHash, int pageType,
      TupleBTreePageValidationProof source) {
    if (reference == null || !reference.isAttached() || !reference.isWritable()
        || !pinned.containsKey(reference)) return StatusCode.INVALID_EXTERNAL_INPUT;
    int pageId = reference.pageId();
    if (pageId <= 0 || pageId >= pages.length
        || reference.page() != pages[pageId]
        || reference.pageGeneration() != generations[pageId]
        || !validation.writableBorrowed(pageId) || source == null
        || !source.matches(pages[pageId], 0, schemaId, descriptorHash, pageType)) {
      return StatusCode.INVARIANT_BROKEN;
    }
    return validation.sealCanonicalMutation(
        pageId, generations[pageId], pages[pageId],
        schemaId, descriptorHash, pageType, source);
  }

  int validationCount() { return validation.validationCount(); }
  int validationMissCount() { return validation.validationMissCount(); }
  int canonicalSealCount() { return validation.canonicalSealCount(); }
  int canonicalValidationReuseCount() {
    return validation.canonicalValidationReuseCount();
  }

  StatusCode bumpPageGeneration(int pageId) {
    if (pageId <= 0 || pageId >= generations.length || generations[pageId] <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (TupleBTreePageReference reference : pinned.keySet()) {
      if (reference.pageId() == pageId) return StatusCode.CONFLICT;
    }
    if (generations[pageId] == Long.MAX_VALUE) return StatusCode.FENCED;
    generations[pageId]++;
    validation.invalidate(pageId);
    return StatusCode.OK;
  }

  private boolean owns(TupleBTreePageReference reference) {
    if (reference == null || !reference.isAttached() || !pinned.containsKey(reference)) {
      return false;
    }
    int pageId = reference.pageId();
    return pageId > 0 && pageId < pages.length
        && reference.page() == pages[pageId] && reference.start() == 0
        && reference.pageGeneration() == generations[pageId];
  }

  @Override
  public StatusCode replaceRoot(int expectedPageId, int replacementPageId) {
    if (rootPageId != expectedPageId || replacementPageId <= 0
        || replacementPageId > pageCount || pages[replacementPageId] == null) {
      return StatusCode.CONFLICT;
    }
    rootPageId = replacementPageId;
    rootGeneration++;
    return StatusCode.OK;
  }

  @Override
  public StatusCode release(TupleBTreePageReference reference) {
    if (reference == null || !reference.isAttached()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (!pinned.containsKey(reference)) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (releasesBeforeFailure == 0) {
      releasesBeforeFailure = -1;
      return StatusCode.IO_FAILURE;
    }
    if (releasesBeforeFailure > 0) releasesBeforeFailure--;
    if (reference.isWritable()) {
      int pageId = reference.pageId();
      StatusCode status = validation.releaseWritable(
          pageId, generations[pageId], pages[pageId]);
      if (!status.isOk()) return status;
    }
    pinned.remove(reference);
    return StatusCode.OK;
  }

  int pageCount() { return pageCount; }

  ByteBuffer page(int pageId) {
    return pageId > 0 && pageId <= pageCount ? pages[pageId] : null;
  }

  void setRootPageId(int pageId) {
    rootPageId = pageId;
    rootGeneration++;
  }

  void bumpRootGeneration() { rootGeneration++; }

  void failNextRelease() { releasesBeforeFailure = 0; }

  void failReleaseAfter(int successfulReleases) {
    releasesBeforeFailure = successfulReleases;
  }
}
