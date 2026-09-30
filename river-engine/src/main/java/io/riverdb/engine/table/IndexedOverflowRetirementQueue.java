package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.btree.TupleRowOverflowCodec;
import io.riverdb.format.btree.TupleRowOverflowHeader;
import io.riverdb.storage.btree.BTreeRootPage;
import java.nio.ByteBuffer;

/** Durable FIFO of detached overflow references, owned by allocation metadata. */
final class IndexedOverflowRetirementQueue {
  private final IndexedPageSet pages;
  private final IndexedOperationPage metadataPin = new IndexedOperationPage();
  private final IndexedOperationPage link = new IndexedOperationPage();
  private final TupleRowOverflowHeader header = new TupleRowOverflowHeader();

  IndexedOverflowRetirementQueue(IndexedPageSet pageSet) { pages = pageSet; }

  StatusCode append(int pageId) {
    StatusCode status = pinMetadata();
    if (!status.isOk()) return status;
    ByteBuffer metadata = metadataPin.payload();
    int count = BTreeRootPage.retiredOverflowCount(metadata);
    if (count == Integer.MAX_VALUE) return releaseMetadata(StatusCode.RESOURCE_EXHAUSTED);
    int tail = BTreeRootPage.retiredOverflowTail(metadata);
    if (tail != 0) status = setNext(tail, pageId);
    if (status.isOk()) BTreeRootPage.publishRetiredOverflow(metadata,
        count == 0 ? pageId : BTreeRootPage.retiredOverflowHead(metadata), pageId, count + 1);
    return releaseMetadata(status);
  }

  StatusCode removeHead(int expectedPageId, int expectedNext) {
    StatusCode status = pinMetadata();
    if (!status.isOk()) return status;
    ByteBuffer metadata = metadataPin.payload();
    int count = BTreeRootPage.retiredOverflowCount(metadata);
    if (count <= 0 || BTreeRootPage.retiredOverflowHead(metadata) != expectedPageId
        || (count == 1 ? expectedNext != 0 : expectedNext == 0)) {
      status = StatusCode.CORRUPTION;
    } else BTreeRootPage.publishRetiredOverflow(metadata, expectedNext,
        count == 1 ? 0 : BTreeRootPage.retiredOverflowTail(metadata), count - 1);
    return releaseMetadata(status);
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
        status = pinMetadata();
        if (!status.isOk()) return status;
        metadata = metadataPin.payload();
        if (previous != 0) status = setNext(previous, next);
        if (status.isOk()) {
          int count = BTreeRootPage.retiredOverflowCount(metadata);
          BTreeRootPage.publishRetiredOverflow(metadata,
              previous == 0 ? next : BTreeRootPage.retiredOverflowHead(metadata),
              next == 0 ? previous : BTreeRootPage.retiredOverflowTail(metadata), count - 1);
        }
        return releaseMetadata(status);
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

  // The root must remain resident while a link acquisition can spill another staged page.
  private StatusCode pinMetadata() {
    return pages.pinScalarOperationPage(IndexedTableKernel.ROOT_META_PAGE_ID, true, metadataPin);
  }

  private StatusCode releaseMetadata(StatusCode status) {
    StatusCode released = pages.releaseOperationPage(metadataPin);
    return status.isOk() ? released : status;
  }
}
