package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.btree.TupleRowOverflowCodec;
import io.riverdb.format.btree.TupleRowOverflowHeader;
import io.riverdb.format.page.PageCodec;
import io.riverdb.storage.btree.BTreeRootPage;
import java.nio.ByteBuffer;

/** Reclaims one checkpointed overflow reference through the logical mutation stream. */
final class IndexedRetiredOverflowReclaimer {
  private final IndexedPageSet pages;
  private final IndexedOperationPage candidate = new IndexedOperationPage();
  private final TupleRowOverflowHeader header = new TupleRowOverflowHeader();
  private int pageId;
  private long generation;
  private long retirementSequence;

  IndexedRetiredOverflowReclaimer(IndexedPageSet pageSet) { pages = pageSet; }

  StatusCode reclaimOne(long keyId, long oldestVisibleCommitSequence) {
    reset();
    ByteBuffer metadata = pages.operationPayload(IndexedTableKernel.ROOT_META_PAGE_ID);
    if (metadata == null || !BTreeRootPage.validate(metadata).isOk()) {
      return StatusCode.CORRUPTION;
    }
    int end = BTreeRootPage.nextPageId(metadata);
    for (int candidateId = BTreeRootPage.FIRST_REUSABLE_PAGE_ID;
        candidateId < end; candidateId++) {
      if (!eligibleIdentity(candidateId, keyId)) continue;
      StatusCode status = pages.pinTupleOverflowOperationPage(
          candidateId, false, keyId, candidate);
      if (!status.isOk()) return status;
      status = TupleRowOverflowCodec.validate(candidate.payload(), 0, 0, header);
      long retiredAt = header.retiredAtCommitSequence();
      long candidateGeneration = candidate.durableGeneration();
      StatusCode released = pages.releaseOperationPage(candidate);
      if (!status.isOk()) return status;
      if (!released.isOk()) return released;
      if (retiredAt == 0 || retiredAt > oldestVisibleCommitSequence
          || pages.hasPinnedPreRetirementTupleReference(keyId, candidateId, retiredAt)) {
        continue;
      }
      status = reclaimExact(keyId, candidateId, candidateGeneration, retiredAt);
      if (!status.isOk()) return status;
      pageId = candidateId;
      generation = candidateGeneration;
      retirementSequence = retiredAt;
      return StatusCode.OK;
    }
    return StatusCode.OK;
  }

  StatusCode reclaimExact(
      long keyId, int candidateId, long expectedGeneration, long expectedRetirementSequence) {
    if (candidateId < BTreeRootPage.FIRST_REUSABLE_PAGE_ID
        || expectedGeneration <= 0 || expectedRetirementSequence <= 0
        || pages.payloadKind(candidateId) != PageCodec.PAYLOAD_KIND_TUPLE_OVERFLOW
        || pages.ownerKeyId(candidateId) != keyId) return StatusCode.CORRUPTION;
    StatusCode status = pages.pinTupleOverflowOperationPage(
        candidateId, false, keyId, candidate);
    if (!status.isOk()) return status;
    if (candidate.durableGeneration() != expectedGeneration) status = StatusCode.CORRUPTION;
    if (status.isOk()) status = TupleRowOverflowCodec.validate(
        candidate.payload(), 0, 0, header);
    if (status.isOk() && header.retiredAtCommitSequence() != expectedRetirementSequence) {
      status = StatusCode.CORRUPTION;
    }
    StatusCode released = pages.releaseOperationPage(candidate);
    if (!status.isOk()) return status;
    if (!released.isOk()) return released;
    ByteBuffer metadata = pages.stageExisting(
        IndexedTableKernel.ROOT_META_PAGE_ID, pages.changedPageCapacity());
    if (metadata == null) return pages.lastStatus();
    ByteBuffer free = pages.stageFreeTuple(
        candidateId, keyId, pages.changedPageCapacity());
    return free == null ? pages.lastStatus()
        : BTreeRootPage.releasePage(metadata, candidateId, free);
  }

  int pageId() { return pageId; }
  long generation() { return generation; }
  long retirementSequence() { return retirementSequence; }

  private boolean eligibleIdentity(int candidateId, long keyId) {
    return pages.isPresent(candidateId) && !pages.isStaged(candidateId)
        && !pages.isPrepared(candidateId) && !pages.isDirty(candidateId)
        && pages.payloadKind(candidateId) == PageCodec.PAYLOAD_KIND_TUPLE_OVERFLOW
        && pages.ownerKeyId(candidateId) == keyId;
  }

  void reset() {
    pageId = 0;
    generation = 0;
    retirementSequence = 0;
  }
}
