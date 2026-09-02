package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.page.PageCodec;
import io.riverdb.storage.btree.BTreePage;
import io.riverdb.storage.btree.BTreeRootPage;
import java.nio.ByteBuffer;

/** Allocation-free scalar-tree traversal through one immutable committed page generation. */
final class IndexedSnapshotTreeLookup {
  private final IndexedPageSet pages;
  private final IndexedPageGenerationPin pin = new IndexedPageGenerationPin();
  private StatusCode lastStatus = StatusCode.OK;

  IndexedSnapshotTreeLookup(IndexedPageSet pageSet) { pages = pageSet; }

  int find(long visibleCommitSequence, long space, long key) {
    lastStatus = StatusCode.OK;
    int pageId = rootPage(visibleCommitSequence);
    if (pageId <= 0) return 0;
    int remaining = maximumDepth(pageId);
    while (remaining-- > 0) {
      StatusCode status = pages.pinPageAt(pageId, visibleCommitSequence, pin);
      if (!status.isOk()) return fail(status);
      ByteBuffer page = pin.payload();
      int next = 0;
      if (!scalarPage()) status = StatusCode.CORRUPTION;
      else if (BTreePage.type(page) == BTreePage.TYPE_LEAF) next = pageId;
      else if (BTreePage.type(page) != BTreePage.TYPE_INTERNAL) {
        status = StatusCode.CORRUPTION;
      } else {
        next = BTreePage.childForKey(page, space, key);
        if (next <= 0) status = StatusCode.CORRUPTION;
      }
      StatusCode released = pages.unpinPage(pin);
      if (status.isOk()) status = released;
      if (!status.isOk()) return fail(status);
      if (next == pageId) return pageId;
      pageId = next;
    }
    return fail(StatusCode.CORRUPTION);
  }

  StatusCode lastStatus() { return lastStatus; }

  private int rootPage(long visibleCommitSequence) {
    StatusCode status = pages.pinPageAt(
        IndexedTableKernel.ROOT_META_PAGE_ID, visibleCommitSequence, pin);
    if (!status.isOk()) return fail(status);
    int root = 0;
    int nextPage = 0;
    if (!scalarPage() || BTreeRootPage.validate(pin.payload()) != StatusCode.OK) {
      status = StatusCode.CORRUPTION;
    } else {
      root = BTreeRootPage.rootPageId(pin.payload());
      nextPage = BTreeRootPage.nextPageId(pin.payload());
    }
    StatusCode released = pages.unpinPage(pin);
    if (status.isOk()) status = released;
    if (!status.isOk() || root <= 0 || root >= nextPage) return fail(
        status.isOk() ? StatusCode.CORRUPTION : status);
    return root;
  }

  private boolean scalarPage() {
    return pin.payloadKind() == PageCodec.PAYLOAD_KIND_SCALAR_BTREE
        && pin.ownerKeyId() == PageCodec.SCALAR_OWNER_KEY_ID;
  }

  private static int maximumDepth(int rootPageId) {
    return Integer.SIZE - Integer.numberOfLeadingZeros(rootPageId) + 1;
  }

  private int fail(StatusCode status) {
    lastStatus = status.isOk() ? StatusCode.CORRUPTION : status;
    return 0;
  }
}
