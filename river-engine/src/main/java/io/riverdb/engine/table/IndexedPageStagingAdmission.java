package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;

/** Owns changed-page admission budgets, member pressure, and staging rollback. */
final class IndexedPageStagingAdmission {
  private final IndexedPageFrameCache cache;
  private final IndexedPageState state;
  private final IndexedPreparedPageBatch prepared;
  private final IndexedStagingPageFrameStore staging;
  private IndexedPreparedLogicalCommit member;
  private boolean capacityPressure;

  IndexedPageStagingAdmission(
      IndexedPageFrameCache owner, IndexedPageState pageState,
      IndexedPreparedPageBatch preparedBatch, IndexedStagingPageFrameStore stagingStore) {
    cache = owner;
    state = pageState;
    prepared = preparedBatch;
    staging = stagingStore;
  }

  boolean admitExisting(int pageId, int maximumChangedPages, boolean alreadyStaged) {
    if (alreadyStaged) return true;
    if (!state.present(pageId) && !prepared.contains(pageId)) {
      cache.setStatus(StatusCode.CORRUPTION);
      return false;
    }
    return admitNew(pageId, maximumChangedPages, false);
  }

  boolean admitNew(int pageId, int maximumChangedPages, boolean alreadyStaged) {
    if (alreadyStaged) return true;
    if (member != null) {
      int memberChangedPages = state.changedPageCount() + 1;
      StatusCode status = prepared.admitMemberPage(memberChangedPages);
      if (status.isOk()) status = member.admitStagedPages(memberChangedPages);
      if (!status.isOk()) {
        cache.setStatus(status);
        markCapacityPressure(status);
        return false;
      }
    }
    StatusCode status = state.addChangedPage(pageId, maximumChangedPages);
    cache.setStatus(status);
    markCapacityPressure(status);
    return status.isOk();
  }

  StatusCode beginMember(IndexedPreparedLogicalCommit memberCommit) {
    if (memberCommit == null || member != null || state.changedPageCount() != 0) {
      return cache.setStatus(StatusCode.INVARIANT_BROKEN);
    }
    member = memberCommit;
    capacityPressure = false;
    return cache.setStatus(StatusCode.OK);
  }

  boolean capacityPressure() { return capacityPressure; }
  void endMember() { member = null; }

  void rollbackMember() {
    clearStagedFlags();
    state.resetChanges();
  }

  void markCapacityPressure(StatusCode status) {
    if (member != null
        && (status == StatusCode.RETRY || status == StatusCode.RESOURCE_EXHAUSTED)) {
      capacityPressure = true;
    }
  }

  void rollback(int pageId, boolean alreadyStaged) {
    if (alreadyStaged) return;
    state.markStaged(pageId, false);
    state.removeChangedPage(pageId);
  }

  void clearStagedFlags() {
    for (int index = 0; index < state.changedPageCount(); index++) {
      int pageId = state.changedPageId(index);
      IndexedPageFrame frame = staging.frame(pageId);
      if (frame != null) {
        state.setIdentity(pageId, frame.previousPayloadKind, frame.previousOwnerKeyId);
      }
      state.markStaged(pageId, false);
      staging.release(pageId);
    }
  }
}
