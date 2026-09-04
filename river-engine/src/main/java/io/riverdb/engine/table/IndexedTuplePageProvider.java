package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.storage.btree.BTreeRootPage;
import io.riverdb.storage.btree.TupleBTreePageProvider;
import io.riverdb.storage.btree.TupleBTreePageReference;
import io.riverdb.format.btree.TupleBTreePageValidationProof;
import java.nio.ByteBuffer;

/** Operation-scoped native PageSet provider for one tuple index. */
final class IndexedTuplePageProvider implements TupleBTreePageProvider {
  private final IndexedPageSet pages;
  private final IndexedTupleRootState root;
  private final int maximumNewPages;
  private final IndexedOperationPage metadata = new IndexedOperationPage();
  private final IndexedOperationPage firstPage = new IndexedOperationPage();
  private final IndexedOperationPage secondPage = new IndexedOperationPage();
  private TupleBTreePageReference firstReference;
  private TupleBTreePageReference secondReference;
  private int plannedPages;
  private int allocatedPages;
  private boolean active;

  IndexedTuplePageProvider(IndexedPageSet pageSet, IndexedTupleRootState rootState) {
    pages = pageSet;
    root = rootState;
    maximumNewPages = pages == null ? 0 : Math.max(0, pages.changedPageCapacity() - 2);
  }

  StatusCode begin(int newPages) {
    if (active || pages == null || root == null
        || newPages < 0 || newPages > maximumNewPages) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = root.begin();
    if (status.isOk() && newPages > 0) {
      status = pages.pinScalarOperationPage(
          IndexedTableKernel.ROOT_META_PAGE_ID, true, metadata);
    }
    if (status.isOk() && newPages > 0) status = admitAllocation(newPages);
    if (!status.isOk()) {
      StatusCode released = releaseMetadata();
      root.cancel();
      return released.isOk() ? status : released;
    }
    plannedPages = newPages;
    allocatedPages = 0;
    active = true;
    return StatusCode.OK;
  }

  StatusCode finish(StatusCode operation) {
    if (!active) return StatusCode.INVARIANT_BROKEN;
    StatusCode status = operation;
    if (firstReference != null || secondReference != null) {
      status = StatusCode.INVARIANT_BROKEN;
    } else if (status.isOk() && allocatedPages != plannedPages) {
      status = StatusCode.INVARIANT_BROKEN;
    }
    StatusCode released = releaseMetadata();
    active = false;
    plannedPages = 0;
    allocatedPages = 0;
    if (!released.isOk()) status = released;
    if (!status.isOk()) root.cancel();
    return status;
  }

  boolean cleanupPending() {
    return metadata.attached();
  }

  boolean reusable() {
    return !active && !root.active() && !metadata.attached()
        && firstReference == null && secondReference == null;
  }

  StatusCode releaseRetained() {
    if (active || firstReference != null || secondReference != null) {
      return StatusCode.CONFLICT;
    }
    return releaseMetadata();
  }

  StatusCode publishRoot() { return active ? StatusCode.CONFLICT : root.publish(); }
  void cancelRoot() { root.cancel(); }

  int ownedPageCount() {
    int count = 0;
    ByteBuffer rootMetadata = pages.operationPayload(IndexedTableKernel.ROOT_META_PAGE_ID);
    int upperPageId = rootMetadata == null ? pages.highestPageId()
        : Math.max(pages.highestPageId(), BTreeRootPage.nextPageId(rootMetadata) - 1);
    for (int pageId = 1; pageId <= upperPageId; pageId++) {
      if ((pages.isPresent(pageId) || pages.isStaged(pageId))
          && pages.payloadKind(pageId)
              == io.riverdb.format.page.PageCodec.PAYLOAD_KIND_TUPLE_BTREE
          && pages.ownerKeyId(pageId) == root.keyId()) count++;
    }
    return count;
  }

  @Override
  public int rootPageId() { return root.rootPageId(); }

  @Override
  public StatusCode pin(
      int pageId, boolean writable, TupleBTreePageReference result) {
    if (!active || result == null || result.isAttached()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    IndexedOperationPage page = freePage();
    if (page == null) return StatusCode.RESOURCE_EXHAUSTED;
    StatusCode status = pages.pinTupleOperationPage(
        pageId, writable, root.keyId(), page);
    if (status.isOk()) status = attach(result, page, writable);
    return status;
  }

  @Override
  public StatusCode restorePageValidation(
      TupleBTreePageReference reference, long schemaId,
      long descriptorHash, int expectedType,
      TupleBTreePageValidationProof target) {
    IndexedOperationPage page = ownedPage(reference);
    return page == null ? StatusCode.INVALID_EXTERNAL_INPUT
        : pages.restorePageValidation(
            page.pageId(), page.pageGeneration(),
            schemaId, descriptorHash, expectedType, target);
  }

  @Override
  public StatusCode rememberPageValidation(
      TupleBTreePageReference reference, long schemaId,
      long descriptorHash, int pageType,
      TupleBTreePageValidationProof source) {
    IndexedOperationPage page = ownedPage(reference);
    return page == null ? StatusCode.INVALID_EXTERNAL_INPUT
        : pages.rememberPageValidation(
            page.pageId(), page.pageGeneration(),
            schemaId, descriptorHash, pageType, source);
  }

  @Override
  public StatusCode consumeCanonicalMutationValidation(
      TupleBTreePageReference reference, long schemaId,
      long descriptorHash, int pageType,
      TupleBTreePageValidationProof target) {
    IndexedOperationPage page = ownedWritablePage(reference);
    return page == null ? StatusCode.INVALID_EXTERNAL_INPUT
        : pages.consumeTupleMutationInputValidation(
            page.pageId(), page.pageGeneration(), root.keyId(),
            schemaId, descriptorHash, pageType, target);
  }

  @Override
  public StatusCode sealCanonicalMutation(
      TupleBTreePageReference reference, long schemaId,
      long descriptorHash, int pageType,
      TupleBTreePageValidationProof source) {
    IndexedOperationPage page = ownedWritablePage(reference);
    if (page == null) return StatusCode.INVARIANT_BROKEN;
    return pages.sealTupleMutationValidation(
        page.pageId(), page.pageGeneration(), root.keyId(),
        schemaId, descriptorHash, pageType, source);
  }

  @Override
  public StatusCode allocate(TupleBTreePageReference result) {
    if (!active || result == null || result.isAttached()
        || allocatedPages >= plannedPages || !metadata.attached()) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    IndexedOperationPage page = freePage();
    if (page == null) return StatusCode.RESOURCE_EXHAUSTED;
    StatusCode status = IndexedOperationPageAllocation.tuple(
        pages, metadata.payload(), root.keyId(), page);
    if (status.isOk()) {
      allocatedPages++;
      status = attach(result, page, true);
    }
    return status;
  }

  @Override
  public StatusCode replaceRoot(int expectedPageId, int replacementPageId) {
    return active ? root.replace(expectedPageId, replacementPageId)
        : StatusCode.INVARIANT_BROKEN;
  }

  @Override
  public StatusCode release(TupleBTreePageReference reference) {
    IndexedOperationPage page = reference == firstReference ? firstPage
        : reference == secondReference ? secondPage : null;
    if (page == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = pages.releaseOperationPage(page);
    if (status.isOk()) {
      if (reference == firstReference) firstReference = null;
      else secondReference = null;
    }
    return status;
  }

  private StatusCode admitAllocation(int count) {
    StatusCode status = BTreeRootPage.validate(metadata.payload());
    if (!status.isOk()) return status;
    return BTreeRootPage.hasAllocations(
        metadata.payload(), count, IndexedTableLimits.MAX_PAGES)
        ? StatusCode.OK : StatusCode.RESOURCE_EXHAUSTED;
  }

  private IndexedOperationPage freePage() {
    return firstReference == null ? firstPage : secondReference == null ? secondPage : null;
  }

  private StatusCode attach(
      TupleBTreePageReference reference, IndexedOperationPage page, boolean writable) {
    StatusCode status = reference.attach(
        page.pageId(), page.payload(), 0, writable, page.pageGeneration());
    if (!status.isOk()) {
      StatusCode released = pages.releaseOperationPage(page);
      return released.isOk() ? status : released;
    }
    if (page == firstPage) firstReference = reference;
    else secondReference = reference;
    return StatusCode.OK;
  }

  private IndexedOperationPage ownedWritablePage(TupleBTreePageReference reference) {
    IndexedOperationPage page = reference == firstReference ? firstPage
        : reference == secondReference ? secondPage : null;
    return active && reference != null && reference.isAttached()
        && reference.isWritable() && page != null && page.attached()
        && page.writable() && page.arena() == IndexedOperationPage.STAGING_ARENA
        && reference.pageId() == page.pageId()
        && reference.page() == page.payload()
        && reference.start() == 0
        && reference.pageGeneration() == page.pageGeneration()
        ? page : null;
  }

  private IndexedOperationPage ownedPage(TupleBTreePageReference reference) {
    IndexedOperationPage page = reference == firstReference ? firstPage
        : reference == secondReference ? secondPage : null;
    return active && reference != null && reference.isAttached()
        && page != null && page.attached()
        && reference.pageId() == page.pageId()
        && reference.page() == page.payload()
        && reference.start() == 0
        && reference.pageGeneration() == page.pageGeneration()
        ? page : null;
  }

  private StatusCode releaseMetadata() {
    return metadata.attached()
        ? pages.releaseOperationPage(metadata) : StatusCode.OK;
  }
}
