package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.btree.TupleRowOverflowCodec;
import io.riverdb.format.btree.TupleRowOverflowHeader;
import io.riverdb.format.page.PageCodec;
import io.riverdb.storage.btree.BTreeRootPage;
import java.nio.ByteBuffer;

/** Reclaims checkpointed queue heads up to the admitted overflow allocation demand. */
final class IndexedRetiredOverflowReclaimer {
  private final IndexedPageSet pages;
  private final IndexedOverflowRetirementQueue queue;
  private final IndexedOperationPage metadata = new IndexedOperationPage();
  private final IndexedOperationPage candidate = new IndexedOperationPage();
  private final TupleRowOverflowHeader header = new TupleRowOverflowHeader();
  private final TupleRowOverflowHeader committedHeader = new TupleRowOverflowHeader();
  private final IndexedPageGenerationPin committed = new IndexedPageGenerationPin();
  private boolean checkpointed;
  private final ByteBuffer record = ByteBuffer.allocate(IndexedOverflowReclamationCodec.BYTES);
  private int count;

  IndexedRetiredOverflowReclaimer(IndexedPageSet pageSet) {
    pages = pageSet;
    queue = new IndexedOverflowRetirementQueue(pageSet);
  }

  StatusCode reclaim(
      int maximum, long oldestVisibleCommitSequence,
      IndexedRelationalMutation mutation, int suboperation, int descriptor) {
    count = 0;
    while (count < maximum) {
      ByteBuffer metadata = pages.operationPayload(IndexedTableKernel.ROOT_META_PAGE_ID);
      if (metadata == null || !BTreeRootPage.validate(metadata).isOk()) return StatusCode.CORRUPTION;
      int pageId = BTreeRootPage.retiredOverflowHead(metadata);
      if (pageId == 0 || !pages.isPresent(pageId) || pages.isDirty(pageId)) return StatusCode.OK;
      long keyId = pages.ownerKeyId(pageId);

      StatusCode status = pages.pinTupleOverflowOperationPage(pageId, false, keyId, candidate);
      if (!status.isOk()) return status;
      status = TupleRowOverflowCodec.validate(candidate.payload(), 0, 0, header);
      long generation = candidate.durableGeneration();
      StatusCode released = pages.releaseOperationPage(candidate);
      if (!status.isOk()) return status;
      if (!released.isOk()) return released;
      long retiredAt = header.retiredAtCommitSequence();
      if (retiredAt == 0) return StatusCode.CORRUPTION;
      status = checkpointedRetirement(pageId, keyId, generation, retiredAt);
      if (!status.isOk()) return status;
      if (!checkpointed) return StatusCode.OK;
      if (retiredAt > oldestVisibleCommitSequence
          || pages.hasPinnedPreRetirementTupleReference(keyId, pageId, retiredAt)) return StatusCode.OK;
      IndexedOverflowReclamationCodec.encode(record, pageId, header.nextRetiredPageId(),
          keyId, generation, retiredAt);
      status = reclaimExact(record, 0);
      if (status.isOk()) status = mutation.appendOverflowReclamation(
          suboperation, descriptor, record, 0, record.capacity());
      if (!status.isOk()) return status;
      count++;
    }
    return StatusCode.OK;
  }

  // Queue-link staging does not invalidate a checkpointed removal. A new retirement does.
  private StatusCode checkpointedRetirement(
      int pageId, long keyId, long generation, long retirement) {
    checkpointed = !pages.isStaged(pageId) && !pages.isPrepared(pageId);
    if (checkpointed) return StatusCode.OK;
    StatusCode status = pages.pinPageAt(pageId, Long.MAX_VALUE - 1, committed);
    if (!status.isOk()) return status;
    if (committed.payloadKind() == PageCodec.PAYLOAD_KIND_TUPLE_OVERFLOW
        && committed.ownerKeyId() == keyId && committed.durableGeneration() == generation) {
      status = TupleRowOverflowCodec.validate(committed.payload(), 0, 0, committedHeader);
      checkpointed = status.isOk() && committedHeader.retiredAtCommitSequence() == retirement;
    }
    StatusCode released = pages.unpinPage(committed);
    return status.isOk() ? released : status;
  }

  StatusCode reclaimExact(ByteBuffer record, int offset) {
    int pageId = IndexedOverflowReclamationCodec.pageId(record, offset);
    long keyId = IndexedOverflowReclamationCodec.keyId(record, offset);
    if (pages.payloadKind(pageId) != PageCodec.PAYLOAD_KIND_TUPLE_OVERFLOW
        || pages.ownerKeyId(pageId) != keyId) return StatusCode.CORRUPTION;
    StatusCode status = pages.pinTupleOverflowOperationPage(pageId, false, keyId, candidate);
    if (!status.isOk()) return status;
    if (candidate.durableGeneration() != IndexedOverflowReclamationCodec.generation(record, offset)) {
      status = StatusCode.CORRUPTION;
    }
    if (status.isOk()) status = TupleRowOverflowCodec.validate(candidate.payload(), 0, 0, header);
    if (status.isOk() && (header.retiredAtCommitSequence()
            != IndexedOverflowReclamationCodec.retirement(record, offset)
        || header.nextRetiredPageId() != IndexedOverflowReclamationCodec.next(record, offset))) {
      status = StatusCode.CORRUPTION;
    }
    StatusCode released = pages.releaseOperationPage(candidate);
    if (!status.isOk()) return status;
    if (!released.isOk()) return released;
    status = queue.removeHead(pageId, header.nextRetiredPageId());
    if (!status.isOk()) return status;
    status = pages.pinScalarOperationPage(IndexedTableKernel.ROOT_META_PAGE_ID, true, metadata);
    if (!status.isOk()) return status;
    ByteBuffer free = pages.stageFreeTuple(pageId, keyId, pages.changedPageCapacity());
    status = free == null ? pages.lastStatus()
        : BTreeRootPage.releasePage(metadata.payload(), pageId, free);
    released = pages.releaseOperationPage(metadata);
    return status.isOk() ? released : status;
  }

  int count() { return count; }
}
