package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;

/** Owns immutable page generations between semantic application and visibility publication. */
final class IndexedPreparedPageBatch {
  private static final int IDLE = 0;
  private static final int BUILDING = 1;
  private static final int INSTALLING = 2;
  private static final int INSTALLED = 3;

  private final int[] pageIds;
  private final int[] frameSlots;
  private final int[] previousFrameSlots;
  private final int[] members;
  private final IndexedPageFrameMap latest;
  private int count;
  private int state = IDLE;

  IndexedPreparedPageBatch(IndexedPageCacheConfig config) {
    pageIds = new int[config.currentFrames()];
    frameSlots = new int[config.currentFrames()];
    previousFrameSlots = new int[config.currentFrames()];
    members = new int[config.currentFrames()];
    java.util.Arrays.fill(frameSlots, -1);
    java.util.Arrays.fill(previousFrameSlots, -1);
    latest = new IndexedPageFrameMap(config.currentMapCapacity());
  }

  StatusCode begin() {
    if (state != IDLE || count != 0) return StatusCode.CONFLICT;
    state = BUILDING;
    return StatusCode.OK;
  }

  StatusCode freeze(
      IndexedPageFrameCache cache, IndexedPageState pageState,
      int member, long oldestVisibleCommitSequence) {
    int changed = pageState.changedPageCount();
    if (state != BUILDING || member < 0 || oldestVisibleCommitSequence < 0
        || changed > pageIds.length - count) return StatusCode.RESOURCE_EXHAUSTED;
    for (int index = 0; index < changed; index++) {
      IndexedPageFrame staging = cache.stagingFrame(pageState.changedPageId(index));
      if (staging == null || staging.pinCount != 0) return StatusCode.INVARIANT_BROKEN;
    }
    int first = count;
    for (int index = 0; index < changed; index++) {
      int pageId = pageState.changedPageId(index);
      IndexedPageFrame staging = cache.stagingFrame(pageId);
      if (staging == null) return StatusCode.INVARIANT_BROKEN;
      int slot = reserve(cache, pageId, oldestVisibleCommitSequence);
      if (slot < 0) return cache.lastStatus();
      freeze(cache, slot, pageId, staging);
      pageIds[count] = pageId;
      frameSlots[count] = slot;
      int previous = latest.find(pageId);
      previousFrameSlots[count] = previous >= 0
          ? previous : cache.currentMap.find(pageId);
      members[count++] = member;
    }
    for (int index = first; index < count; index++) {
      latest.put(pageIds[index], frameSlots[index]);
      pageState.markStaged(pageIds[index], false);
      cache.releaseStagingFrame(pageIds[index]);
    }
    pageState.resetChanges();
    return StatusCode.OK;
  }

  StatusCode install(
      IndexedPageFrameCache cache, IndexedPageState pageState,
      long[] commitSequences, int memberCount, long start, long end) {
    StatusCode status = validate(cache, commitSequences, memberCount, start, end);
    if (status.isOk()) status = reserveState(pageState);
    if (!status.isOk()) return status;
    this.state = INSTALLING;
    for (int index = 0; index < count; index++) {
      install(
          cache, pageState, frameSlots[index], previousFrameSlots[index], pageIds[index],
          commitSequences[members[index]], start, end);
    }
    this.state = INSTALLED;
    return StatusCode.OK;
  }

  StatusCode release(IndexedPageFrameCache cache) {
    if (state != INSTALLED) return StatusCode.CONFLICT;
    for (int index = 0; index < count; index++) {
      if (!validFrame(cache, frameSlots[index], pageIds[index])) {
        return StatusCode.INVARIANT_BROKEN;
      }
    }
    clear(cache, true);
    return StatusCode.OK;
  }

  void cancel(IndexedPageFrameCache cache) {
    if (state == BUILDING) clear(cache, false);
  }

  IndexedPageFrame frame(int pageId, IndexedPageFrame[] frames) {
    if (state == IDLE) return null;
    int slot = latest.find(pageId);
    return slot < 0 ? null : frames[slot];
  }

  boolean contains(int pageId) { return state != IDLE && latest.find(pageId) >= 0; }
  int slot(int pageId) { return state == IDLE ? -1 : latest.find(pageId); }
  boolean active() { return state != IDLE; }

  private StatusCode validate(
      IndexedPageFrameCache cache, long[] sequences,
      int memberCount, long start, long end) {
    if (state != BUILDING || sequences == null || memberCount <= 0
        || memberCount > sequences.length || start < 0 || end < start) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    long previous = 0;
    for (int member = 0; member < memberCount; member++) {
      if (sequences[member] <= previous) return StatusCode.INVARIANT_BROKEN;
      previous = sequences[member];
    }
    for (int index = 0; index < count; index++) {
      if (members[index] < 0 || members[index] >= memberCount
          || !validFrame(cache, frameSlots[index], pageIds[index])
          || !validPredecessor(cache, index)) {
        return StatusCode.INVARIANT_BROKEN;
      }
    }
    return StatusCode.OK;
  }

  private StatusCode reserveState(IndexedPageState pageState) {
    for (int index = 0; index < count; index++) {
      StatusCode status = pageState.reservePublication(pageIds[index]);
      if (!status.isOk()) {
        for (int rollback = 0; rollback < index; rollback++) {
          pageState.cancelReservation(pageIds[rollback]);
        }
        return status;
      }
    }
    return StatusCode.OK;
  }

  private void clear(IndexedPageFrameCache cache, boolean published) {
    for (int index = count - 1; index >= 0; index--) {
      latest.remove(pageIds[index]);
      releaseFrame(cache, frameSlots[index], published);
      pageIds[index] = 0;
      frameSlots[index] = -1;
      previousFrameSlots[index] = -1;
      members[index] = 0;
    }
    count = 0;
    state = IDLE;
  }

  private static int reserve(
      IndexedPageFrameCache cache, int pageId, long oldestVisibleCommitSequence) {
    int slot = cache.reusableCurrentSlot(true, oldestVisibleCommitSequence);
    IndexedPageFrame frame = slot < 0 ? null : cache.frameAt(cache.currentFrames, slot);
    StatusCode status = frame == null ? StatusCode.RESOURCE_EXHAUSTED
        : frame.pageId == 0 ? StatusCode.OK : cache.prepareCurrentSlotForReuse(slot);
    if (!status.isOk() || frame == null || frame.publicationReserved || frame.pinCount != 0) {
      cache.setStatus(status.isOk() ? StatusCode.INVARIANT_BROKEN : status);
      return -1;
    }
    frame.publicationReserved = true;
    frame.pinCount = 1;
    return slot;
  }

  private static void freeze(
      IndexedPageFrameCache cache, int slot, int pageId, IndexedPageFrame staging) {
    IndexedPageFrame frame = cache.currentFrames[slot];
    frame.pageId = pageId;
    frame.clearGeneration();
    frame.publicationReserved = true;
    frame.beginPageGeneration(cache.nextPageGeneration());
    IndexedPageSet.copyPage(staging.page, frame.page);
    frame.copyPageValidationFrom(staging);
    frame.identity(staging.payloadKind, staging.ownerKeyId);
    frame.recordStart = 0;
    frame.recordEnd = 0;
    frame.dirty = false;
  }

  private static boolean validFrame(IndexedPageFrameCache cache, int slot, int pageId) {
    if (slot < 0 || slot >= cache.currentFrames.length) return false;
    IndexedPageFrame frame = cache.currentFrames[slot];
    return frame != null && frame.pageId == pageId
        && frame.publicationReserved && frame.pinCount == 1;
  }

  private boolean validPredecessor(IndexedPageFrameCache cache, int index) {
    int previousSlot = previousFrameSlots[index];
    if (previousSlot < 0) return cache.currentMap.find(pageIds[index]) < 0;
    if (previousSlot >= cache.currentFrames.length) return false;
    IndexedPageFrame previous = cache.currentFrames[previousSlot];
    if (previous == null || previous.pageId != pageIds[index]) return false;
    return previous.publicationReserved
        || cache.currentMap.find(pageIds[index]) == previousSlot
            && previous.validUntilCommitSequence == Long.MAX_VALUE;
  }

  private static void install(
      IndexedPageFrameCache cache, IndexedPageState state,
      int slot, int previousSlot, int pageId, long sequence, long start, long end) {
    IndexedPageFrame frame = cache.currentFrames[slot];
    IndexedPageFrame previous = previousSlot < 0 ? null : cache.currentFrames[previousSlot];
    frame.currentGeneration(sequence, previousSlot);
    frame.dirty = true;
    frame.recordStart = start;
    frame.recordEnd = end;
    if (previous != null) {
      previous.validUntilCommitSequence = sequence;
      previous.nextVersionSlot = slot;
    }
    cache.currentMap.put(pageId, slot);
    state.publishPrepared(pageId, frame.payloadKind, frame.ownerKeyId, start, end);
  }

  private static void releaseFrame(
      IndexedPageFrameCache cache, int slot, boolean published) {
    if (slot < 0 || slot >= cache.currentFrames.length) return;
    IndexedPageFrame frame = cache.currentFrames[slot];
    if (frame == null || !frame.publicationReserved || frame.pinCount != 1) return;
    frame.pinCount = 0;
    frame.publicationReserved = false;
    if (published) return;
    frame.pageId = 0;
    frame.dirty = false;
    frame.recordStart = 0;
    frame.recordEnd = 0;
    frame.clearGeneration();
  }
}
