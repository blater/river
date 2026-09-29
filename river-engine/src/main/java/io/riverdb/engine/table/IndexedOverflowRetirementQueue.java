package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.btree.TupleRowOverflowCodec;
import io.riverdb.format.btree.TupleRowOverflowHeader;
import io.riverdb.storage.btree.BTreeRootPage;
import java.nio.ByteBuffer;

/** Durable FIFO of detached overflow references, owned by allocation metadata. */
final class IndexedOverflowRetirementQueue {
  private final IndexedPageSet pages;
  private final IndexedOperationPage link = new IndexedOperationPage();
  private final TupleRowOverflowHeader header = new TupleRowOverflowHeader();

  IndexedOverflowRetirementQueue(IndexedPageSet pageSet) { pages = pageSet; }

  StatusCode append(int pageId) {
    ByteBuffer metadata = writableMetadata();
    if (metadata == null) return pages.lastStatus();
    int count = BTreeRootPage.retiredOverflowCount(metadata);
    if (count == Integer.MAX_VALUE) return StatusCode.RESOURCE_EXHAUSTED;
    int tail = BTreeRootPage.retiredOverflowTail(metadata);
    if (tail != 0) {
      StatusCode status = setNext(tail, pageId);
      if (!status.isOk()) return status;
    }
    BTreeRootPage.publishRetiredOverflow(metadata,
        count == 0 ? pageId : BTreeRootPage.retiredOverflowHead(metadata), pageId, count + 1);
    return StatusCode.OK;
  }

  StatusCode removeHead(int expectedPageId, int expectedNext) {
    ByteBuffer metadata = writableMetadata();
    if (metadata == null) return pages.lastStatus();
    int count = BTreeRootPage.retiredOverflowCount(metadata);
    if (count <= 0 || BTreeRootPage.retiredOverflowHead(metadata) != expectedPageId
        || (count == 1 ? expectedNext != 0 : expectedNext == 0)) {
      return StatusCode.CORRUPTION;
    }
    BTreeRootPage.publishRetiredOverflow(metadata, expectedNext,
        count == 1 ? 0 : BTreeRootPage.retiredOverflowTail(metadata), count - 1);
    return StatusCode.OK;
  }

  /** Drop cleanup removes a reference before its link bytes can be reused. */
  StatusCode removeForDrop(int pageId) {
    ByteBuffer metadata = pages.operationPayload(IndexedTableKernel.ROOT_META_PAGE_ID);
    if (metadata == null) return pages.lastStatus();
    int remaining = BTreeRootPage.retiredOverflowCount(metadata);
    int current = BTreeRootPage.retiredOverflowHead(metadata);
    int previous = 0;
    while (remaining-- > 0) {
      StatusCode status = readNext(current);
      if (!status.isOk()) return status;
      int next = header.nextRetiredPageId();
      if (current == pageId) {
        metadata = writableMetadata();
        if (metadata == null) return pages.lastStatus();
        if (previous != 0) {
          status = setNext(previous, next);
          if (!status.isOk()) return status;
        }
        int count = BTreeRootPage.retiredOverflowCount(metadata);
        BTreeRootPage.publishRetiredOverflow(metadata,
            previous == 0 ? next : BTreeRootPage.retiredOverflowHead(metadata),
            next == 0 ? previous : BTreeRootPage.retiredOverflowTail(metadata), count - 1);
        return StatusCode.OK;
      }
      previous = current;
      current = next;
    }
    return current == 0 ? StatusCode.OK : StatusCode.CORRUPTION;
  }

  private StatusCode readNext(int pageId) {
    StatusCode status = pages.pinTupleOverflowOperationPage(
        pageId, false, pages.ownerKeyId(pageId), link);
    if (!status.isOk()) return status;
    status = TupleRowOverflowCodec.validate(link.payload(), 0, 0, header);
    StatusCode released = pages.releaseOperationPage(link);
    return status.isOk() ? released : status;
  }

  private StatusCode setNext(int pageId, int next) {
    StatusCode status = pages.pinTupleOverflowOperationPage(
        pageId, true, pages.ownerKeyId(pageId), link);
    if (!status.isOk()) return status;
    status = TupleRowOverflowCodec.linkRetired(link.payload(), 0, next, header);
    StatusCode released = pages.releaseOperationPage(link);
    return status.isOk() ? released : status;
  }

  private ByteBuffer writableMetadata() {
    return pages.stageExisting(IndexedTableKernel.ROOT_META_PAGE_ID, pages.changedPageCapacity());
  }
}
