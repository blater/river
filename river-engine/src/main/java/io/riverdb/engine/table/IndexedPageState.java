package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.runtime.DatabasePageCachePlan;

/** Bounded page publication metadata shared by the frame cache and facade. */
final class IndexedPageState {
  private static final int[] DETACHED_CHANGED_PAGES = new int[0];
  private final IndexedPageStateValues values;
  private int[] changedPageIds;
  private int changedPageCount;
  private int highestPageId;
  private long stagedCopyBytes;
  private boolean pageImageOperation;

  IndexedPageState(DatabasePageCachePlan config) {
    values = new IndexedPageStateValues(config);
    changedPageIds = new int[config.activeStagedPages()];
  }

  boolean present(int pageId) {
    return pageId > 0 && pageId <= highestPageId;
  }

  boolean staged(int pageId) {
    return pageId > 0 && pageId <= IndexedTableLimits.MAX_PAGES && values.staged(pageId);
  }

  boolean dirty(int pageId) {
    return pageId > 0 && pageId <= IndexedTableLimits.MAX_PAGES && values.dirty(pageId);
  }

  long recordStart(int pageId) { return values.start(pageId); }
  long recordEnd(int pageId) { return values.end(pageId); }
  int payloadKind(int pageId) { return values.kind(pageId); }
  long ownerKeyId(int pageId) { return values.owner(pageId); }
  int changedPageCount() { return changedPageCount; }
  int changedPageCapacity() { return changedPageIds.length; }
  int changedPageId(int index) { return changedPageIds[index]; }
  int highestPageId() { return highestPageId; }
  long stagedCopyBytes() { return stagedCopyBytes; }
  void addCopyBytes(long bytes) { stagedCopyBytes += bytes; }
  void beginPageImageOperation() { pageImageOperation = true; }

  void resetChanges() {
    changedPageCount = 0;
    pageImageOperation = false;
  }

  StatusCode installPresent(int pageId) {
    if (pageId <= 0 || pageId > IndexedTableLimits.MAX_PAGES) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    highestPageId = Math.max(highestPageId, pageId);
    return StatusCode.OK;
  }

  StatusCode setIdentity(int pageId, int kind, long owner) {
    return values.identity(pageId, kind, owner);
  }

  StatusCode markChanged(int pageId, long start, long end) {
    return values.changed(pageId, start, end);
  }

  void markClean(int pageId) { values.clean(pageId); }

  void markRebased(int pageId) {
    values.clean(pageId);
  }

  StatusCode addChangedPage(int pageId, int maximumChangedPages) {
    int maximum = pageImageOperation
        ? Math.min(maximumChangedPages, IndexedTableLimits.MAX_CHANGED_PAGES)
        : maximumChangedPages;
    if (changedPageCount >= maximum) return StatusCode.RESOURCE_EXHAUSTED;
    if (changedPageCount >= changedPageIds.length) return StatusCode.RESOURCE_EXHAUSTED;
    StatusCode status = values.reserve(pageId);
    if (!status.isOk()) return status;
    changedPageIds[changedPageCount] = pageId;
    values.staged(pageId, true);
    changedPageCount++;
    return StatusCode.OK;
  }

  void removeChangedPage(int pageId) {
    for (int index = changedPageCount - 1; index >= 0; index--) {
      if (changedPageIds[index] != pageId) continue;
      for (int move = index + 1; move < changedPageCount; move++) {
        changedPageIds[move - 1] = changedPageIds[move];
      }
      changedPageIds[--changedPageCount] = 0;
      return;
    }
  }

  void markStaged(int pageId, boolean value) { values.staged(pageId, value); }

  StatusCode reserveIdentity(int pageId) {
    return values.reserve(pageId);
  }

  StatusCode reservePublication(int pageId) {
    return values.reserve(pageId);
  }

  void publishPrepared(
      int pageId, int kind, long owner, long start, long end) {
    highestPageId = Math.max(highestPageId, pageId);
    values.publish(pageId, kind, owner, start, end);
  }

  void cancelReservation(int pageId) { values.releaseReservation(pageId); }

  boolean hasDirtyPages() { return values.hasDirtyPages(); }
  int metadataEntryCount() { return values.count(); }
  int metadataCapacity() { return values.capacity(); }

  StatusCode detach() {
    if (changedPageCount != 0 || pageImageOperation || hasDirtyPages()) {
      return StatusCode.CONFLICT;
    }
    StatusCode status = values.detach();
    if (status.isOk()) changedPageIds = DETACHED_CHANGED_PAGES;
    return status;
  }

  void abandon() {
    values.abandon();
    changedPageIds = DETACHED_CHANGED_PAGES;
    changedPageCount = highestPageId = 0;
    stagedCopyBytes = 0;
    pageImageOperation = false;
  }
}
