package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.format.page.PageCodec;
import io.riverdb.format.page.PageHeader;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.IoResult;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

/** Bounded current/staging frame cache with pin-aware eviction. */
final class IndexedPageFrameCache {
  final IndexedPageState state;
  private final IndexedPageFrameIo io;
  final IndexedPageFrame[] currentFrames;
  final IndexedPageFrame[] stagingFrames;
  final IndexedPageFrameMap currentMap;
  final IndexedPageFrameMap stagingMap;
  final IndexedPreparedPageBatch prepared;
  private final IndexedOperationPagePins operationPins;
  private long accessClock;
  private long pageGenerationClock;
  private int currentProbeCursor;
  private StatusCode lastStatus = StatusCode.OK;
  private final IoResult cacheIo = new IoResult();

  IndexedPageFrameCache(
      DurableFile backingFile,
      DurableFile stagingFile,
      DatabaseIncarnation database,
      WalGeneration generation,
      IndexedPageState pageState,
      IndexedPageCacheConfig config) {
    state = pageState;
    io = new IndexedPageFrameIo(backingFile, stagingFile, database, generation, state);
    currentFrames = new IndexedPageFrame[config.currentFrames()];
    stagingFrames = new IndexedPageFrame[config.stagingFrames()];
    currentMap = new IndexedPageFrameMap(config.currentMapCapacity());
    stagingMap = new IndexedPageFrameMap(config.stagingMapCapacity());
    prepared = new IndexedPreparedPageBatch(config);
    operationPins = new IndexedOperationPagePins(this);
    for (int index = 0; index < config.eagerFrames(); index++) {
      currentFrames[index] = new IndexedPageFrame();
      stagingFrames[index] = new IndexedPageFrame();
    }
  }

  void setGeneration(WalGeneration generation) { io.setGeneration(generation); }

  ByteBuffer currentPayloadUnchecked(int pageId) {
    IndexedPageFrame frame = currentFrame(pageId, true);
    return frame == null ? null : frame.payload;
  }

  StatusCode pinCurrentPage(int pageId) {
    if (!validPageId(pageId) || !state.present(pageId)) {
      lastStatus = StatusCode.CORRUPTION;
      return lastStatus;
    }
    IndexedPageFrame frame = currentFrame(pageId, true);
    if (frame == null) return lastStatus;
    frame.pinCount++;
    lastStatus = StatusCode.OK;
    return StatusCode.OK;
  }

  StatusCode pinPageAt(
      int pageId, long visibleCommitSequence, IndexedPageGenerationPin result) {
    if (!validPageId(pageId) || visibleCommitSequence < 0 || result == null
        || result.active() || !state.present(pageId)) {
      return setStatus(StatusCode.INVALID_EXTERNAL_INPUT);
    }
    IndexedPageFrame current = currentFrame(pageId, true);
    if (current == null) return lastStatus;
    int slot = currentMap.find(pageId);
    while (slot >= 0) {
      IndexedPageFrame frame = currentFrames[slot];
      if (frame.validFromCommitSequence <= visibleCommitSequence
          && visibleCommitSequence < frame.validUntilCommitSequence) {
        frame.pinCount++;
        frame.access = ++accessClock;
        result.set(
            slot, pageId, frame.validFromCommitSequence, frame.pageGeneration,
            frame.payload, frame.payloadKind, frame.ownerKeyId);
        return setStatus(StatusCode.OK);
      }
      slot = frame.previousVersionSlot;
    }
    return setStatus(StatusCode.CORRUPTION);
  }

  StatusCode unpinPage(IndexedPageGenerationPin pin) {
    if (pin == null || !pin.active() || pin.slot() >= currentFrames.length) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    IndexedPageFrame frame = currentFrames[pin.slot()];
    if (frame == null || frame.pageId != pin.pageId()
        || frame.validFromCommitSequence != pin.validFromCommitSequence()
        || frame.pinCount <= 0 || frame.payload != pin.payload()) {
      return StatusCode.INVARIANT_BROKEN;
    }
    frame.pinCount--;
    pin.reset();
    return StatusCode.OK;
  }

  boolean pageValidationMatches(
      int pageId, long pageGeneration, long schemaId,
      long descriptorHash, int expectedType) {
    IndexedPageFrame frame = frameForGeneration(pageId, pageGeneration);
    return frame != null && frame.pageValidationMatches(
        pageGeneration, schemaId, descriptorHash, expectedType);
  }

  void rememberPageValidation(
      int pageId, long pageGeneration, long schemaId,
      long descriptorHash, int pageType) {
    IndexedPageFrame frame = frameForGeneration(pageId, pageGeneration);
    if (frame != null) frame.rememberPageValidation(schemaId, descriptorHash, pageType);
  }

  StatusCode pinOperationPage(
      int pageId, boolean writable, IndexedOperationPage result) {
    return operationPins.pin(
        pageId, writable, IndexedTableLimits.MAX_CHANGED_PAGES, result);
  }

  StatusCode pinTupleOperationPage(
      int pageId, boolean writable, long ownerKeyId, IndexedOperationPage result) {
    if (ownerKeyId <= 0 || !identityMatches(
        pageId, PageCodec.PAYLOAD_KIND_TUPLE_BTREE, ownerKeyId)) {
      lastStatus = StatusCode.CORRUPTION;
      return lastStatus;
    }
    return operationPins.pin(
        pageId, writable, IndexedTableLimits.MAX_LOGICAL_CHANGED_PAGES, result);
  }

  StatusCode pinScalarOperationPage(
      int pageId, boolean writable, IndexedOperationPage result) {
    if (!identityMatches(
        pageId, PageCodec.PAYLOAD_KIND_SCALAR_BTREE,
        PageCodec.SCALAR_OWNER_KEY_ID)) {
      lastStatus = StatusCode.CORRUPTION;
      return lastStatus;
    }
    return operationPins.pin(
        pageId, writable, IndexedTableLimits.MAX_LOGICAL_CHANGED_PAGES, result);
  }

  StatusCode pinNewOperationPage(int pageId, IndexedOperationPage result) {
    return operationPins.pinNew(
        pageId, IndexedTableLimits.MAX_CHANGED_PAGES,
        PageCodec.PAYLOAD_KIND_SCALAR_BTREE,
        PageCodec.SCALAR_OWNER_KEY_ID, result);
  }

  StatusCode pinNewOperationPage(
      int pageId, int payloadKind, long ownerKeyId, IndexedOperationPage result) {
    return operationPins.pinNew(
        pageId, IndexedTableLimits.MAX_LOGICAL_CHANGED_PAGES,
        payloadKind, ownerKeyId, result);
  }

  StatusCode releaseOperationPage(IndexedOperationPage page) {
    return operationPins.release(page);
  }

  void unpinCurrentPage(int pageId) {
    int slot = currentMap.find(pageId);
    if (slot < 0) return;
    IndexedPageFrame frame = currentFrames[slot];
    if (frame.pinCount > 0) frame.pinCount--;
  }

  StatusCode markCurrentChanged(int pageId, long start, long end) {
    IndexedPageFrame frame = currentFrame(pageId, false);
    if (frame == null) {
      lastStatus = StatusCode.RESOURCE_EXHAUSTED;
      return lastStatus;
    }
    StatusCode status = state.markChanged(pageId, start, end);
    if (!status.isOk()) return setStatus(status);
    frame.dirty = true;
    frame.recordStart = start;
    frame.recordEnd = end;
    lastStatus = StatusCode.OK;
    return StatusCode.OK;
  }

  StatusCode reidentifyCurrent(int pageId, int payloadKind, long ownerKeyId) {
    if (!state.present(pageId)
        || payloadKind(pageId) != PageCodec.PAYLOAD_KIND_FREE
        || !IndexedPageIdentity.valid(payloadKind, ownerKeyId)) {
      return setStatus(StatusCode.CORRUPTION);
    }
    IndexedPageFrame frame = currentFrame(pageId, true);
    if (frame == null) return lastStatus;
    frame.identity(payloadKind, ownerKeyId);
    return setStatus(StatusCode.OK);
  }

  void markClean(int pageId) {
    state.markClean(pageId);
    IndexedPageFrame frame = currentFrame(pageId, false);
    if (frame != null) {
      frame.dirty = false;
      frame.recordStart = 0;
      frame.recordEnd = 0;
    }
  }

  void markRebased(int pageId) {
    state.markRebased(pageId);
    IndexedPageFrame frame = currentFrame(pageId, false);
    if (frame != null) {
      frame.dirty = false;
      frame.recordStart = 0;
      frame.recordEnd = 0;
    }
  }

  StatusCode encodeCurrent(
      int pageId, DatabaseIncarnation database, WalGeneration generation,
      long start, long end, CRC32C checksum) {
    IndexedPageFrame frame = currentFrame(pageId, true);
    return frame == null ? lastStatus : io.encode(frame, database, generation, start, end, checksum);
  }

  StatusCode encodeStaged(
      int pageId, DatabaseIncarnation database, WalGeneration generation,
      long start, long end, CRC32C checksum) {
    IndexedPageFrame frame = stagingFrame(pageId);
    return frame == null ? lastStatus : io.encode(frame, database, generation, start, end, checksum);
  }

  StatusCode readCurrent(DurableFile file, int pageId, long offset, IoResult result) {
    IndexedPageFrame frame = currentFrameForRead(pageId);
    if (frame == null) return lastStatus;
    StatusCode status = io.read(file, frame, offset, result);
    if (!status.isOk()) releaseCurrentFrame(pageId);
    return status;
  }

  StatusCode writeCurrent(DurableFile file, int pageId, long offset, IoResult result) {
    IndexedPageFrame frame = currentFrame(pageId, true);
    return frame == null ? lastStatus : io.write(file, frame, offset, result);
  }

  StatusCode validateCurrent(int pageId, PageHeader header, CRC32C checksum) {
    IndexedPageFrame frame = currentFrame(pageId, true);
    return frame == null ? lastStatus : io.validate(frame, header, checksum);
  }

  StatusCode validateRecord(ByteBuffer source, int offset, PageHeader header, CRC32C checksum) {
    return io.validateRecord(source, offset, header, checksum);
  }

  void copyStagedToRecord(int pageId, ByteBuffer target, int targetOffset) {
    IndexedPageFrame frame = stagingFrame(pageId);
    if (frame == null) return;
    copyPage(frame.page, target, targetOffset);
  }

  StatusCode installFromRecord(
      ByteBuffer source, int sourceOffset, int pageId, long start, long end) {
    StatusCode status = state.reservePublication(pageId);
    if (!status.isOk()) return setStatus(status);
    IndexedPageFrame frame = currentFrameForRead(pageId);
    if (frame == null) {
      state.cancelReservation(pageId);
      return lastStatus;
    }
    copyFromRecord(source, sourceOffset, frame.page);
    frame.invalidatePageValidation();
    status = io.captureIdentity(frame);
    if (!status.isOk()) {
      releaseCurrentFrame(pageId);
      state.cancelReservation(pageId);
      return status;
    }
    state.installPresent(pageId);
    return markCurrentChanged(pageId, start, end);
  }

  ByteBuffer currentPayload(int pageId) {
    return state.present(pageId) ? currentPayloadUnchecked(pageId) : null;
  }

  ByteBuffer stageExisting(int pageId, int maximumChangedPages) {
    if (!validPageId(pageId)) return null;
    int existingSlot = stagingMap.find(pageId);
    if (existingSlot >= 0) return stagingFrames[existingSlot].payload;
    boolean alreadyStaged = state.staged(pageId);
    if (!admitExistingStaging(pageId, maximumChangedPages, alreadyStaged)) return null;
    IndexedPageFrame staging = acquireStagingFrame(pageId);
    if (staging == null) {
      rollbackAdmission(pageId, alreadyStaged);
      return null;
    }
    StatusCode status = populateExistingStaging(pageId, staging, alreadyStaged);
    if (!status.isOk()) {
      releaseStagingFrame(pageId);
      rollbackAdmission(pageId, alreadyStaged);
      return null;
    }
    state.addCopyBytes(PageCodec.PAGE_BYTES);
    lastStatus = StatusCode.OK;
    return staging.payload;
  }

  ByteBuffer operationPayload(int pageId) {
    if (!validPageId(pageId)) return null;
    IndexedPageFrame staging = stagingFrame(pageId);
    if (staging != null) return staging.payload;
    IndexedPageFrame prepared = preparedFrame(pageId);
    if (prepared != null) return prepared.payload;
    return state.present(pageId) ? currentPayloadUnchecked(pageId) : null;
  }

  ByteBuffer stageNew(int pageId, int maximumChangedPages) {
    return stageNew(
        pageId, maximumChangedPages,
        PageCodec.PAYLOAD_KIND_SCALAR_BTREE, PageCodec.SCALAR_OWNER_KEY_ID);
  }

  ByteBuffer stageNew(
      int pageId, int maximumChangedPages, int payloadKind, long ownerKeyId) {
    boolean recycled = state.present(pageId)
        && payloadKind(pageId) == PageCodec.PAYLOAD_KIND_FREE
        && ownerKeyId(pageId) == PageCodec.SCALAR_OWNER_KEY_ID;
    if (!validPageId(pageId) || state.present(pageId) && !recycled
        || !IndexedPageIdentity.valid(payloadKind, ownerKeyId)) {
      lastStatus = StatusCode.INVALID_EXTERNAL_INPUT;
      return null;
    }
    int existingSlot = stagingMap.find(pageId);
    if (existingSlot >= 0) {
      IndexedPageFrame existing = stagingFrames[existingSlot];
      if (existing.payloadKind != payloadKind || existing.ownerKeyId != ownerKeyId) {
        lastStatus = StatusCode.CORRUPTION;
        return null;
      }
      return existing.payload;
    }
    boolean alreadyStaged = state.staged(pageId);
    if (!matchingStagedIdentity(pageId, payloadKind, ownerKeyId, alreadyStaged)) {
      lastStatus = StatusCode.CORRUPTION;
      return null;
    }
    if (!admitNewStaging(pageId, maximumChangedPages, alreadyStaged)) return null;
    IndexedPageFrame staging = acquireStagingFrame(pageId);
    if (staging == null) {
      rollbackAdmission(pageId, alreadyStaged);
      return null;
    }
    return prepareNewStaging(
        pageId, payloadKind, ownerKeyId, staging, alreadyStaged);
  }

  ByteBuffer stageFreeTuple(int pageId, long ownerKeyId, int maximumChangedPages) {
    if (!identityMatches(pageId, PageCodec.PAYLOAD_KIND_TUPLE_BTREE, ownerKeyId)) {
      lastStatus = StatusCode.CORRUPTION;
      return null;
    }
    ByteBuffer payload = stageExisting(pageId, maximumChangedPages);
    IndexedPageFrame staging = stagingFrame(pageId);
    if (payload == null || staging == null) return null;
    staging.rememberIdentity(
        PageCodec.PAYLOAD_KIND_TUPLE_BTREE, ownerKeyId);
    staging.invalidatePageValidation();
    for (int index = 0; index < PageCodec.PAGE_BYTES; index++) {
      staging.page.put(index, (byte) 0);
    }
    staging.payload.clear();
    staging.identity(PageCodec.PAYLOAD_KIND_FREE, PageCodec.SCALAR_OWNER_KEY_ID);
    state.setIdentity(pageId, PageCodec.PAYLOAD_KIND_FREE, PageCodec.SCALAR_OWNER_KEY_ID);
    lastStatus = StatusCode.OK;
    return staging.payload;
  }

  private boolean admitExistingStaging(
      int pageId, int maximumChangedPages, boolean alreadyStaged) {
    if (alreadyStaged) return true;
    if (!state.present(pageId) && !prepared.contains(pageId)) {
      lastStatus = StatusCode.CORRUPTION;
      return false;
    }
    return admitNewStaging(pageId, maximumChangedPages, false);
  }

  private boolean admitNewStaging(
      int pageId, int maximumChangedPages, boolean alreadyStaged) {
    if (alreadyStaged) return true;
    StatusCode status = state.addChangedPage(pageId, maximumChangedPages);
    lastStatus = status;
    return status.isOk();
  }

  private StatusCode populateExistingStaging(
      int pageId, IndexedPageFrame staging, boolean alreadyStaged) {
    if (alreadyStaged) return io.loadStaged(staging);
    IndexedPageFrame current = preparedFrame(pageId);
    if (current == null) current = currentFrame(pageId, true);
    if (current == null) return lastStatus;
    copyPage(current.page, staging.page);
    staging.identity(current.payloadKind, current.ownerKeyId);
    staging.rememberIdentity(current.payloadKind, current.ownerKeyId);
    return state.setIdentity(pageId, current.payloadKind, current.ownerKeyId);
  }

  private ByteBuffer prepareNewStaging(
      int pageId,
      int payloadKind,
      long ownerKeyId,
    IndexedPageFrame staging,
      boolean alreadyStaged) {
    if (alreadyStaged) return loadNewStaging(pageId, staging);
    IndexedPageFrame current = state.present(pageId) ? currentFrame(pageId, true) : null;
    if (state.present(pageId) && current == null) {
      releaseStagingFrame(pageId);
      rollbackAdmission(pageId, false);
      return null;
    }
    staging.rememberIdentity(
        current == null ? PageCodec.PAYLOAD_KIND_SCALAR_BTREE : current.payloadKind,
        current == null ? PageCodec.SCALAR_OWNER_KEY_ID : current.ownerKeyId);
    staging.invalidatePageValidation();
    for (int index = 0; index < PageCodec.PAGE_BYTES; index++) {
      staging.page.put(index, (byte) 0);
    }
    staging.payload.clear();
    staging.identity(payloadKind, ownerKeyId);
    StatusCode identity = state.setIdentity(pageId, payloadKind, ownerKeyId);
    if (!identity.isOk()) {
      releaseStagingFrame(pageId);
      rollbackAdmission(pageId, false);
      lastStatus = identity;
      return null;
    }
    lastStatus = StatusCode.OK;
    return staging.payload;
  }

  private ByteBuffer loadNewStaging(int pageId, IndexedPageFrame staging) {
    StatusCode status = io.loadStaged(staging);
    staging.identity(state.payloadKind(pageId), state.ownerKeyId(pageId));
    if (status.isOk()) {
      lastStatus = StatusCode.OK;
      return staging.payload;
    }
    releaseStagingFrame(pageId);
    lastStatus = status;
    return null;
  }

  private boolean matchingStagedIdentity(
      int pageId, int payloadKind, long ownerKeyId, boolean alreadyStaged) {
    return !alreadyStaged || state.payloadKind(pageId) == payloadKind
        && state.ownerKeyId(pageId) == ownerKeyId;
  }

  private void rollbackAdmission(int pageId, boolean alreadyStaged) {
    if (alreadyStaged) return;
    state.markStaged(pageId, false);
    state.removeChangedPage(pageId);
  }

  StatusCode ensureBuffers(int pageId) {
    if (!validPageId(pageId)) {
      lastStatus = StatusCode.INVALID_EXTERNAL_INPUT;
      return lastStatus;
    }
    IndexedPageFrame frame = currentFrame(pageId, state.present(pageId));
    if (frame == null && !state.present(pageId)) frame = acquireCurrentFrame(pageId, true);
    if (frame == null) return lastStatus;
    lastStatus = StatusCode.OK;
    return StatusCode.OK;
  }

  StatusCode retainBuffer(int pageId) {
    StatusCode status = ensureBuffers(pageId);
    if (!status.isOk()) return status;
    int slot = currentMap.find(pageId);
    if (slot < 0) {
      lastStatus = StatusCode.INVARIANT_BROKEN;
      return lastStatus;
    }
    currentFrames[slot].pinCount++;
    return StatusCode.OK;
  }

  void releaseBuffer(int pageId) {
    int slot = currentMap.find(pageId);
    if (slot >= 0 && currentFrames[slot].pinCount > 0) {
      currentFrames[slot].pinCount--;
    }
  }

  StatusCode reclaimHistorical(long oldestVisibleCommitSequence) {
    if (oldestVisibleCommitSequence < 0) return setStatus(StatusCode.INVALID_EXTERNAL_INPUT);
    for (int slot = 0; slot < currentFrames.length; slot++) {
      IndexedPageFrame frame = currentFrames[slot];
      if (frame == null || frame.pageId == 0 || frame.pinCount != 0
          || frame.publicationReserved || frame.nextVersionSlot < 0
          || frame.validUntilCommitSequence > oldestVisibleCommitSequence) continue;
      StatusCode status = prepareCurrentSlotForReuse(slot);
      if (!status.isOk()) return setStatus(status);
    }
    return setStatus(StatusCode.OK);
  }

  StatusCode beginPreparedBatch() {
    return setStatus(prepared.begin());
  }

  StatusCode freezeChangedPages(int member, long oldestVisibleCommitSequence) {
    return setStatus(prepared.freeze(this, state, member, oldestVisibleCommitSequence));
  }

  StatusCode installPreparedPages(
      long[] commitSequences, int memberCount, long start, long end) {
    return setStatus(prepared.install(this, state, commitSequences, memberCount, start, end));
  }

  StatusCode releasePreparedBatch() {
    return setStatus(prepared.release(this));
  }

  void cancelPreparedBatch() {
    prepared.cancel(this);
  }

  boolean validPresentPage(int pageId) { return state.present(pageId); }
  boolean operationPresentPage(int pageId) {
    return state.present(pageId) || prepared.contains(pageId);
  }
  boolean hasDirtyPages() { return state.hasDirtyPages(); }
  boolean addChangedPage(int pageId, int maximum) {
    StatusCode status = state.addChangedPage(pageId, maximum);
    lastStatus = status;
    return status.isOk();
  }

  void clearStagedFlags() {
    for (int index = 0; index < state.changedPageCount(); index++) {
      int pageId = state.changedPageId(index);
      IndexedPageFrame frame = stagingFrame(pageId);
      if (frame != null) {
        state.setIdentity(pageId, frame.previousPayloadKind, frame.previousOwnerKeyId);
      }
      state.markStaged(pageId, false);
      releaseStagingFrame(pageId);
    }
  }

  ByteBuffer beginVacuumPage(int pageId) {
    if (!validPageId(pageId) || !state.present(pageId) || state.staged(pageId)) {
      lastStatus = StatusCode.CORRUPTION;
      return null;
    }
    IndexedPageFrame current = currentFrame(pageId, true);
    if (current == null) return null;
    IndexedPageFrame staging = acquireStagingFrame(pageId);
    if (staging == null) return null;
    copyPage(current.page, staging.page);
    staging.identity(current.payloadKind, current.ownerKeyId);
    state.addCopyBytes(PageCodec.PAGE_BYTES);
    lastStatus = StatusCode.OK;
    return staging.payload;
  }

  ByteBuffer vacuumPayload(int pageId) {
    if (!validPageId(pageId) || !state.present(pageId) || state.staged(pageId)) {
      lastStatus = StatusCode.CORRUPTION;
      return null;
    }
    int slot = stagingMap.find(pageId);
    if (slot >= 0) return stagingFrames[slot].payload;
    IndexedPageFrame current = currentFrame(pageId, true);
    if (current == null) return null;
    IndexedPageFrame staging = acquireStagingFrame(pageId);
    if (staging == null) return null;
    StatusCode status = io.loadStaged(staging);
    if (!status.isOk()) {
      releaseStagingFrame(pageId);
      lastStatus = status;
      return null;
    }
    staging.identity(current.payloadKind, current.ownerKeyId);
    lastStatus = StatusCode.OK;
    return staging.payload;
  }

  StatusCode sealVacuumPage(int pageId) {
    int slot = stagingMap.find(pageId);
    if (slot < 0) return setStatus(StatusCode.CORRUPTION);
    StatusCode status = io.writeStaged(stagingFrames[slot]);
    if (status.isOk()) releaseStagingFrame(pageId);
    return setStatus(status);
  }

  StatusCode publishVacuumPage(int pageId, long start, long end) {
    ByteBuffer shadow = vacuumPayload(pageId);
    if (shadow == null) return lastStatus;
    IndexedPageFrame staging = stagingFrames[stagingMap.find(pageId)];
    IndexedPageFrame current = currentFrame(pageId, true);
    if (current == null) return lastStatus;
    current.beginPageGeneration(nextPageGeneration());
    copyPage(staging.page, current.page);
    current.copyPageValidationFrom(staging);
    current.identity(staging.payloadKind, staging.ownerKeyId);
    StatusCode status = state.markChanged(pageId, start, end);
    if (status.isOk()) {
      current.dirty = true;
      current.recordStart = start;
      current.recordEnd = end;
      status = io.writeBack(current);
    }
    if (status.isOk()) releaseStagingFrame(pageId);
    return setStatus(status);
  }

  StatusCode forceVacuumPublication() {
    return setStatus(io.forceBacking());
  }

  void discardVacuumPages() {
    for (IndexedPageFrame frame : stagingFrames) {
      if (frame == null || frame.pageId == 0) continue;
      stagingMap.remove(frame.pageId);
      frame.pageId = 0;
      frame.invalidatePageValidation();
    }
    lastStatus = StatusCode.OK;
  }

  StatusCode lastStatus() { return lastStatus; }
  int changedPageCount() { return state.changedPageCount(); }
  int changedPageId(int index) { return state.changedPageId(index); }
  int highestPageId() { return state.highestPageId(); }
  long stagedCopyBytes() { return state.stagedCopyBytes(); }
  long recordStart(int pageId) {
    if (state.dirty(pageId)) return state.recordStart(pageId);
    IndexedPageFrame frame = currentFrame(pageId, true);
    return frame == null ? 0 : frame.recordStart;
  }
  long recordEnd(int pageId) {
    if (state.dirty(pageId)) return state.recordEnd(pageId);
    IndexedPageFrame frame = currentFrame(pageId, true);
    return frame == null ? 0 : frame.recordEnd;
  }
  int payloadKind(int pageId) {
    if (state.staged(pageId)) return state.payloadKind(pageId);
    IndexedPageFrame prepared = preparedFrame(pageId);
    if (prepared != null) return prepared.payloadKind;
    int slot = currentMap.find(pageId);
    if (slot >= 0) return currentFrames[slot].payloadKind;
    if (!state.present(pageId)) return PageCodec.PAYLOAD_KIND_SCALAR_BTREE;
    IndexedPageFrame frame = currentFrame(pageId, true);
    return frame == null ? PageCodec.PAYLOAD_KIND_SCALAR_BTREE : frame.payloadKind;
  }
  long ownerKeyId(int pageId) {
    if (state.staged(pageId)) return state.ownerKeyId(pageId);
    IndexedPageFrame prepared = preparedFrame(pageId);
    if (prepared != null) return prepared.ownerKeyId;
    int slot = currentMap.find(pageId);
    if (slot >= 0) return currentFrames[slot].ownerKeyId;
    if (!state.present(pageId)) return PageCodec.SCALAR_OWNER_KEY_ID;
    IndexedPageFrame frame = currentFrame(pageId, true);
    return frame == null ? PageCodec.SCALAR_OWNER_KEY_ID : frame.ownerKeyId;
  }

  IndexedPageFrame currentFrame(int pageId, boolean load) {
    int slot = currentMap.find(pageId);
    if (slot >= 0) {
      IndexedPageFrame frame = currentFrames[slot];
      frame.access = ++accessClock;
      return frame;
    }
    return load && state.present(pageId) ? loadCurrentFrame(pageId) : null;
  }

  private IndexedPageFrame currentFrameForRead(int pageId) {
    IndexedPageFrame frame = currentFrame(pageId, false);
    return frame == null ? acquireCurrentFrame(pageId, true) : frame;
  }

  private IndexedPageFrame loadCurrentFrame(int pageId) {
    IndexedPageFrame frame = acquireCurrentFrame(pageId, true);
    if (frame == null) return null;
    StatusCode status = io.readCurrent(frame, cacheIo);
    if (!status.isOk()) {
      releaseCurrentFrame(pageId);
      lastStatus = status;
      return null;
    }
    return frame;
  }

  StatusCode setStatus(StatusCode status) {
    lastStatus = status;
    return status;
  }

  private boolean identityMatches(int pageId, int payloadKind, long ownerKeyId) {
    if (!validPageId(pageId)) return false;
    if (state.staged(pageId)) {
      return state.payloadKind(pageId) == payloadKind
          && state.ownerKeyId(pageId) == ownerKeyId;
    }
    IndexedPageFrame prepared = preparedFrame(pageId);
    if (prepared != null) {
      return prepared.payloadKind == payloadKind && prepared.ownerKeyId == ownerKeyId;
    }
    if (!state.present(pageId)) return false;
    IndexedPageFrame frame = currentFrame(pageId, true);
    if (frame == null) return false;
    return frame.payloadKind == payloadKind && frame.ownerKeyId == ownerKeyId;
  }

  private IndexedPageFrame acquireCurrentFrame(int pageId, boolean allowEviction) {
    int existing = currentMap.find(pageId);
    if (existing >= 0) return currentFrames[existing];
    int slot = reusableCurrentSlot(allowEviction, Long.MIN_VALUE);
    if (slot < 0) return fail(StatusCode.RESOURCE_EXHAUSTED);
    IndexedPageFrame frame = frameAt(currentFrames, slot);
    if (frame == null) return null;
    if (frame.pageId != 0) {
      StatusCode status = prepareCurrentSlotForReuse(slot);
      if (!status.isOk()) return fail(status);
    }
    frame.pageId = pageId;
    frame.beginPageGeneration(nextPageGeneration());
    frame.identity(PageCodec.PAYLOAD_KIND_SCALAR_BTREE, PageCodec.SCALAR_OWNER_KEY_ID);
    frame.recordStart = 0;
    frame.recordEnd = 0;
    frame.dirty = false;
    frame.clearGeneration();
    frame.access = ++accessClock;
    currentMap.put(pageId, slot);
    return frame;
  }

  int reusableCurrentSlot(boolean allowEviction, long oldestVisibleCommitSequence) {
    for (int probe = 0; probe < currentFrames.length; probe++) {
      int index = currentProbeCursor;
      currentProbeCursor = (currentProbeCursor + 1) % currentFrames.length;
      IndexedPageFrame frame = currentFrames[index];
      if (frame == null || frame.pageId == 0 && !frame.publicationReserved) return index;
      if (frame.publicationReserved || frame.pinCount != 0) continue;
      if (frame.nextVersionSlot >= 0
          && frame.validUntilCommitSequence <= oldestVisibleCommitSequence) {
        return index;
      }
      if (allowEviction && !state.staged(frame.pageId)
          && frame.nextVersionSlot < 0 && frame.previousVersionSlot < 0) return index;
    }
    return -1;
  }

  StatusCode prepareCurrentSlotForReuse(int slot) {
    IndexedPageFrame frame = currentFrames[slot];
    if (frame == null || frame.pinCount != 0 || frame.publicationReserved) {
      return StatusCode.INVARIANT_BROKEN;
    }
    if (frame.nextVersionSlot >= 0) {
      IndexedPageFrame newer = currentFrames[frame.nextVersionSlot];
      if (newer == null || newer.previousVersionSlot != slot) {
        return StatusCode.INVARIANT_BROKEN;
      }
      newer.previousVersionSlot = frame.previousVersionSlot;
      if (frame.previousVersionSlot >= 0) {
        IndexedPageFrame older = currentFrames[frame.previousVersionSlot];
        if (older == null || older.nextVersionSlot != slot) {
          return StatusCode.INVARIANT_BROKEN;
        }
        older.nextVersionSlot = frame.nextVersionSlot;
      }
    } else {
      if (frame.previousVersionSlot >= 0 || currentMap.find(frame.pageId) != slot) {
        return StatusCode.INVARIANT_BROKEN;
      }
      StatusCode status = io.writeBack(frame);
      if (!status.isOk()) return status;
      currentMap.remove(frame.pageId);
    }
    frame.pageId = 0;
    frame.dirty = false;
    frame.recordStart = 0;
    frame.recordEnd = 0;
    frame.clearGeneration();
    return StatusCode.OK;
  }

  private IndexedPageFrame acquireStagingFrame(int pageId) {
    int existing = stagingMap.find(pageId);
    if (existing >= 0) return stagingFrames[existing];
    int slot = IndexedPageFrameSelection.reusable(stagingFrames, true);
    if (slot < 0) return fail(null);
    IndexedPageFrame frame = frameAt(stagingFrames, slot);
    if (frame == null) return null;
    if (frame.pageId != 0) {
      StatusCode status = io.writeStaged(frame);
      if (!status.isOk()) return fail(status);
      stagingMap.remove(frame.pageId);
    }
    frame.pageId = pageId;
    frame.beginPageGeneration(nextPageGeneration());
    IndexedPageFrame prepared = preparedFrame(pageId);
    frame.identity(
        prepared == null ? state.payloadKind(pageId) : prepared.payloadKind,
        prepared == null ? state.ownerKeyId(pageId) : prepared.ownerKeyId);
    frame.dirty = false;
    frame.access = ++accessClock;
    stagingMap.put(pageId, slot);
    return frame;
  }

  private IndexedPageFrame fail(StatusCode status) {
    lastStatus = status == null ? StatusCode.RESOURCE_EXHAUSTED : status;
    return null;
  }

  long nextPageGeneration() {
    pageGenerationClock++;
    if (pageGenerationClock <= 0) pageGenerationClock = 1;
    return pageGenerationClock;
  }

  IndexedPageFrame frameAt(IndexedPageFrame[] frames, int slot) {
    IndexedPageFrame frame = frames[slot];
    if (frame != null) return frame;
    try {
      frame = new IndexedPageFrame();
      frames[slot] = frame;
      return frame;
    } catch (OutOfMemoryError error) {
      return fail(StatusCode.RESOURCE_EXHAUSTED);
    }
  }

  IndexedPageFrame stagingFrame(int pageId) {
    int slot = stagingMap.find(pageId);
    if (slot < 0) {
      if (!state.staged(pageId)) return null;
      IndexedPageFrame frame = acquireStagingFrame(pageId);
      if (frame == null) return null;
      StatusCode status = io.loadStaged(frame);
      frame.identity(state.payloadKind(pageId), state.ownerKeyId(pageId));
      if (status.isOk()) return frame;
      releaseStagingFrame(pageId);
      lastStatus = status;
      return null;
    }
    IndexedPageFrame frame = stagingFrames[slot];
    frame.access = ++accessClock;
    return frame;
  }

  private void releaseCurrentFrame(int pageId) {
    int slot = currentMap.find(pageId);
    if (slot < 0) return;
    currentMap.remove(pageId);
    currentFrames[slot].pageId = 0;
    currentFrames[slot].clearGeneration();
  }

  void releaseStagingFrame(int pageId) {
    int slot = stagingMap.find(pageId);
    if (slot < 0) return;
    stagingMap.remove(pageId);
    stagingFrames[slot].pageId = 0;
    stagingFrames[slot].invalidatePageValidation();
  }

  private IndexedPageFrame frameForGeneration(int pageId, long pageGeneration) {
    if (!validPageId(pageId) || pageGeneration <= 0) return null;
    int slot = stagingMap.find(pageId);
    if (slot >= 0) {
      IndexedPageFrame frame = stagingFrames[slot];
      if (frame != null && frame.pageGeneration == pageGeneration) return frame;
    }
    IndexedPageFrame preparedGeneration = preparedFrame(pageId);
    if (preparedGeneration != null
        && preparedGeneration.pageGeneration == pageGeneration) return preparedGeneration;
    slot = currentMap.find(pageId);
    while (slot >= 0) {
      IndexedPageFrame frame = currentFrames[slot];
      if (frame == null) return null;
      if (frame.pageGeneration == pageGeneration) return frame;
      slot = frame.previousVersionSlot;
    }
    return null;
  }

  IndexedPageFrame preparedFrame(int pageId) {
    IndexedPageFrame frame = prepared.frame(pageId, currentFrames);
    if (frame != null) frame.access = ++accessClock;
    return frame;
  }

  private static void copyPage(ByteBuffer source, ByteBuffer target) {
    target.put(0, source, 0, PageCodec.PAGE_BYTES);
    target.position(0);
    target.limit(PageCodec.PAGE_BYTES);
  }

  private static void copyPage(ByteBuffer source, ByteBuffer target, int offset) {
    for (int index = 0; index < PageCodec.PAGE_BYTES; index++) {
      target.put(offset + index, source.get(index));
    }
  }

  private static void copyFromRecord(ByteBuffer source, int offset, ByteBuffer target) {
    for (int index = 0; index < PageCodec.PAGE_BYTES; index++) {
      target.put(index, source.get(offset + index));
    }
    target.position(0);
    target.limit(PageCodec.PAGE_BYTES);
  }

  static boolean validPageId(int pageId) {
    return pageId > 0 && pageId <= IndexedTableLimits.MAX_PAGES;
  }

}
