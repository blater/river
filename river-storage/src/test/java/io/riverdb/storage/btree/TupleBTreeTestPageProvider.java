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
  private final long[] validatedGenerations;
  private final TupleBTreePageValidationProof[] validations;
  private final long[] mutationInputGenerations;
  private final TupleBTreePageValidationProof[] mutationInputValidations;
  private final long[] pendingMutationGenerations;
  private final TupleBTreePageValidationProof[] pendingMutationValidations;
  private final boolean[] writableBorrowed;
  private final IdentityHashMap<TupleBTreePageReference, Boolean> pinned =
      new IdentityHashMap<>();
  private int pageCount;
  private int rootPageId;
  private long rootGeneration;
  private int releasesBeforeFailure = -1;
  private int validationCount;
  private int validationMissCount;
  private int canonicalSealCount;
  private int canonicalValidationReuseCount;

  TupleBTreeTestPageProvider(int maximumPages) {
    pages = new ByteBuffer[maximumPages + 1];
    generations = new long[maximumPages + 1];
    validatedGenerations = new long[maximumPages + 1];
    validations = new TupleBTreePageValidationProof[maximumPages + 1];
    mutationInputGenerations = new long[maximumPages + 1];
    mutationInputValidations = new TupleBTreePageValidationProof[maximumPages + 1];
    pendingMutationGenerations = new long[maximumPages + 1];
    pendingMutationValidations = new TupleBTreePageValidationProof[maximumPages + 1];
    writableBorrowed = new boolean[maximumPages + 1];
    for (int index = 0; index <= maximumPages; index++) {
      validations[index] = new TupleBTreePageValidationProof();
      mutationInputValidations[index] = new TupleBTreePageValidationProof();
      pendingMutationValidations[index] = new TupleBTreePageValidationProof();
    }
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
    if (writable && writableBorrowed[pageId]) return StatusCode.INVARIANT_BROKEN;
    StatusCode status = result.attach(
        pageId, pages[pageId], 0, writable, generations[pageId]);
    if (status.isOk() && writable) beginWritableBorrow(pageId);
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
    invalidateValidation(pageId);
    StatusCode status = result.attach(pageId, pages[pageId], 0, true, generations[pageId]);
    if (status.isOk()) {
      writableBorrowed[pageId] = true;
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
    boolean matches = pageId > 0 && pageId < pages.length && !writableBorrowed[pageId]
        && reference.pageGeneration() == generations[pageId]
        && validatedGenerations[pageId] == generations[pageId]
        && validations[pageId].matches(
            pages[pageId], 0, schemaId, descriptorHash, expectedType);
    if (!matches) validationMissCount++;
    if (!matches) {
      if (target != null) target.reset();
      return StatusCode.CONFLICT;
    }
    return validations[pageId].lendTo(pages[pageId], 0, target);
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
    if (writableBorrowed[pageId]) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = source.copyTo(pages[pageId], 0, validations[pageId]);
    if (!status.isOk()) return status;
    validatedGenerations[pageId] = generations[pageId];
    validationCount++;
    return StatusCode.OK;
  }

  @Override
  public StatusCode consumeCanonicalMutationValidation(
      TupleBTreePageReference reference, long schemaId,
      long descriptorHash, int pageType,
      TupleBTreePageValidationProof target) {
    if (reference == null || !reference.isAttached() || !reference.isWritable()
        || !pinned.containsKey(reference)) return StatusCode.INVALID_EXTERNAL_INPUT;
    int pageId = reference.pageId();
    boolean matches = pageId > 0 && pageId < pages.length
        && writableBorrowed[pageId]
        && reference.page() == pages[pageId]
        && reference.pageGeneration() == generations[pageId]
        && mutationInputGenerations[pageId] == generations[pageId]
        && mutationInputValidations[pageId].matches(
            pages[pageId], 0, schemaId, descriptorHash, pageType);
    StatusCode status = matches
        ? mutationInputValidations[pageId].copyTo(pages[pageId], 0, target)
        : StatusCode.CONFLICT;
    discardMutationInputValidation(pageId);
    if (matches) canonicalValidationReuseCount++;
    if (!matches && target != null) target.reset();
    return status;
  }

  @Override
  public StatusCode sealCanonicalMutation(
      TupleBTreePageReference reference, long schemaId,
      long descriptorHash, int pageType,
      TupleBTreePageValidationProof source) {
    if (reference == null || !reference.isAttached() || !reference.isWritable()
        || !pinned.containsKey(reference)) return StatusCode.INVALID_EXTERNAL_INPUT;
    int pageId = reference.pageId();
    if (pageId <= 0 || pageId >= pages.length || reference.page() != pages[pageId]
        || reference.pageGeneration() != generations[pageId]
        || !writableBorrowed[pageId] || source == null || !source.matches(
            pages[pageId], 0, schemaId, descriptorHash, pageType)) {
      return StatusCode.INVARIANT_BROKEN;
    }
    StatusCode status = source.copyTo(
        pages[pageId], 0, pendingMutationValidations[pageId]);
    if (!status.isOk()) return status;
    pendingMutationGenerations[pageId] = generations[pageId];
    return StatusCode.OK;
  }

  int validationCount() { return validationCount; }
  int validationMissCount() { return validationMissCount; }
  int canonicalSealCount() { return canonicalSealCount; }
  int canonicalValidationReuseCount() { return canonicalValidationReuseCount; }

  StatusCode bumpPageGeneration(int pageId) {
    if (pageId <= 0 || pageId >= generations.length || generations[pageId] <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (TupleBTreePageReference reference : pinned.keySet()) {
      if (reference.pageId() == pageId) return StatusCode.CONFLICT;
    }
    if (generations[pageId] == Long.MAX_VALUE) return StatusCode.FENCED;
    generations[pageId]++;
    invalidateValidation(pageId);
    return StatusCode.OK;
  }

  private void invalidateValidation(int pageId) {
    discardMutationInputValidation(pageId);
    discardPendingMutationValidation(pageId);
    validations[pageId].reset();
    validatedGenerations[pageId] = 0;
  }

  private void beginWritableBorrow(int pageId) {
    discardMutationInputValidation(pageId);
    discardPendingMutationValidation(pageId);
    if (validatedGenerations[pageId] == generations[pageId]) {
      validations[pageId].copyTo(
          pages[pageId], 0, mutationInputValidations[pageId]);
      mutationInputGenerations[pageId] = generations[pageId];
    }
    validations[pageId].reset();
    validatedGenerations[pageId] = 0;
    writableBorrowed[pageId] = true;
  }

  private void discardMutationInputValidation(int pageId) {
    mutationInputValidations[pageId].reset();
    mutationInputGenerations[pageId] = 0;
  }

  private void discardPendingMutationValidation(int pageId) {
    pendingMutationValidations[pageId].reset();
    pendingMutationGenerations[pageId] = 0;
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
      if (pendingMutationGenerations[pageId] != 0) {
        if (pendingMutationGenerations[pageId] != generations[pageId]
            || !pendingMutationValidations[pageId].matchesPage(pages[pageId], 0)) {
          return StatusCode.INVARIANT_BROKEN;
        }
        StatusCode status = pendingMutationValidations[pageId].copyTo(
            pages[pageId], 0, validations[pageId]);
        if (!status.isOk()) return status;
        validatedGenerations[pageId] = generations[pageId];
        canonicalSealCount++;
      }
      discardPendingMutationValidation(pageId);
      discardMutationInputValidation(reference.pageId());
      writableBorrowed[reference.pageId()] = false;
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
