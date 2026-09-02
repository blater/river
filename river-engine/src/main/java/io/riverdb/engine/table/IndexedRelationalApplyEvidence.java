package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.storage.btree.BTreeRootPage;
import java.nio.ByteBuffer;

/** Checks scalar-root and allocation-watermark CAS evidence around each suboperation. */
final class IndexedRelationalApplyEvidence {
  private final IndexedPageSet pages;

  IndexedRelationalApplyEvidence(IndexedPageSet pageSet) { pages = pageSet; }

  StatusCode expected(IndexedRelationalMutationBuffer source, int operation) {
    return matches(
        source.expectedScalarRootAt(operation), source.expectedNextPageAt(operation));
  }

  StatusCode resulting(IndexedRelationalMutationBuffer source, int operation) {
    return matches(
        source.resultingScalarRootAt(operation), source.resultingNextPageAt(operation));
  }

  private StatusCode matches(int root, int nextPage) {
    ByteBuffer metadata = pages.operationPayload(IndexedTableKernel.ROOT_META_PAGE_ID);
    if (metadata == null) return pages.lastStatus();
    return BTreeRootPage.validate(metadata).isOk()
        && BTreeRootPage.rootPageId(metadata) == root
        && BTreeRootPage.nextPageId(metadata) == nextPage
            ? StatusCode.OK : StatusCode.CORRUPTION;
  }
}
