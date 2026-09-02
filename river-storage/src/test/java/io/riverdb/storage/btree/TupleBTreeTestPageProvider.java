package io.riverdb.storage.btree;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.page.PageCodec;
import java.nio.ByteBuffer;
import java.util.IdentityHashMap;

/** Fixed-capacity in-memory provider used to verify the storage ownership contract. */
final class TupleBTreeTestPageProvider implements TupleBTreePageProvider {
  private final ByteBuffer[] pages;
  private final long[] generations;
  private final long[] validatedGenerations;
  private final long[] validatedSchemaIds;
  private final long[] validatedDescriptorHashes;
  private final int[] validatedTypes;
  private final IdentityHashMap<TupleBTreePageReference, Boolean> pinned =
      new IdentityHashMap<>();
  private int pageCount;
  private int rootPageId;
  private long rootGeneration;
  private int releasesBeforeFailure = -1;
  private int validationCount;
  private int validationMissCount;

  TupleBTreeTestPageProvider(int maximumPages) {
    pages = new ByteBuffer[maximumPages + 1];
    generations = new long[maximumPages + 1];
    validatedGenerations = new long[maximumPages + 1];
    validatedSchemaIds = new long[maximumPages + 1];
    validatedDescriptorHashes = new long[maximumPages + 1];
    validatedTypes = new int[maximumPages + 1];
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
    StatusCode status = result.attach(
        pageId, pages[pageId], 0, writable, generations[pageId]);
    if (status.isOk() && writable) invalidateValidation(pageId);
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
    if (status.isOk()) pinned.put(result, Boolean.TRUE);
    return status;
  }

  @Override
  public boolean pageValidationMatches(
      TupleBTreePageReference reference, long schemaId,
      long descriptorHash, int expectedType) {
    if (reference == null || !reference.isAttached()) return false;
    int pageId = reference.pageId();
    boolean matches = pageId > 0 && pageId < pages.length
        && reference.pageGeneration() == generations[pageId]
        && validatedGenerations[pageId] == generations[pageId]
        && validatedSchemaIds[pageId] == schemaId
        && validatedDescriptorHashes[pageId] == descriptorHash
        && (expectedType <= 0 || validatedTypes[pageId] == expectedType);
    if (!matches) validationMissCount++;
    return matches;
  }

  @Override
  public void rememberPageValidation(
      TupleBTreePageReference reference, long schemaId,
      long descriptorHash, int pageType) {
    if (reference == null || !reference.isAttached()) return;
    int pageId = reference.pageId();
    if (pageId <= 0 || pageId >= pages.length
        || reference.pageGeneration() != generations[pageId]) return;
    validatedSchemaIds[pageId] = schemaId;
    validatedDescriptorHashes[pageId] = descriptorHash;
    validatedTypes[pageId] = pageType;
    validatedGenerations[pageId] = generations[pageId];
    validationCount++;
  }

  int validationCount() { return validationCount; }
  int validationMissCount() { return validationMissCount; }

  void bumpPageGeneration(int pageId) {
    if (pageId <= 0 || pageId >= generations.length || generations[pageId] <= 0) return;
    generations[pageId]++;
    invalidateValidation(pageId);
  }

  private void invalidateValidation(int pageId) {
    validatedGenerations[pageId] = 0;
    validatedSchemaIds[pageId] = 0;
    validatedDescriptorHashes[pageId] = 0;
    validatedTypes[pageId] = 0;
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
