package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.storage.btree.BTreeRootPage;
import io.riverdb.storage.btree.TupleBTreePageProvider;
import io.riverdb.storage.btree.TupleBTreePageReference;
import java.nio.ByteBuffer;

/** Operation-scoped native PageSet provider for one tuple index. */
final class IndexedTuplePageProvider implements TupleBTreePageProvider {
  private static final int MAXIMUM_NEW_PAGES =
      IndexedTableLimits.MAX_LOGICAL_CHANGED_PAGES - 2;
  private final IndexedPageSet pages;
  private final IndexedTupleRootState root;
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
  }

  StatusCode begin(int newPages) {
    if (active || pages == null || root == null
        || newPages < 0 || newPages > MAXIMUM_NEW_PAGES) {
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
  public boolean pageValidationMatches(
      TupleBTreePageReference reference, long schemaId,
      long descriptorHash, int expectedType) {
    return pages.pageValidationMatches(
        reference.pageId(), reference.pageGeneration(),
        schemaId, descriptorHash, expectedType);
  }

  @Override
  public void rememberPageValidation(
      TupleBTreePageReference reference, long schemaId,
      long descriptorHash, int pageType) {
    pages.rememberPageValidation(
        reference.pageId(), reference.pageGeneration(),
        schemaId, descriptorHash, pageType);
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

  private StatusCode releaseMetadata() {
    return metadata.attached()
        ? pages.releaseOperationPage(metadata) : StatusCode.OK;
  }
}
