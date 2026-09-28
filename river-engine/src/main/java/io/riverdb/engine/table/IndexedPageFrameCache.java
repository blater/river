package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.runtime.DatabasePageCachePlan;
import io.riverdb.format.btree.TupleBTreePageValidationProof;
import io.riverdb.format.page.PageHeader;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.IoResult;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

/** Stable package surface coordinating bounded current and staging frame owners. */
final class IndexedPageFrameCache {
  final IndexedPageState state;
  IndexedPageFrame[] currentFrames;
  IndexedPageFrame[] stagingFrames;
  IndexedPageFrameMap currentMap;
  IndexedPageFrameMap stagingMap;
  IndexedPreparedPageBatch prepared;
  private final IndexedPageFrameCacheOperations operations;
  private long pageGenerationClock;
  private StatusCode lastStatus = StatusCode.OK;

  IndexedPageFrameCache(
      DurableFile backingFile, DurableFile stagingFile,
      DatabaseIncarnation database, WalGeneration generation,
      IndexedPageState pageState, DatabasePageCachePlan config) {
    state = pageState;
    operations = new IndexedPageFrameCacheOperations(
        this, backingFile, stagingFile, database, generation, state, config);
    prepared = operations.prepared();
    syncFrameViews();
  }

  void setGeneration(WalGeneration generation) { operations.io().setGeneration(generation); }
  StatusCode detach() { return operations.lifecycle().detach(); }
  void abandon() { operations.lifecycle().abandon(); }
  void syncFrameViews() { operations.syncFrameViews(this); }

  ByteBuffer currentPayloadUnchecked(int pageId) {
    return operations.metadata().currentPayloadUnchecked(pageId);
  }
  StatusCode pinCurrentPage(int pageId) { return operations.pinning().pinCurrentPage(pageId); }
  void unpinCurrentPage(int pageId) { operations.current().unpin(pageId); }
  StatusCode pinPageAt(
      int pageId, long visibleCommitSequence, IndexedPageGenerationPin result) {
    return operations.pinning().pinPageAt(pageId, visibleCommitSequence, result);
  }
  StatusCode unpinPage(IndexedPageGenerationPin pin) { return operations.pinning().unpinPage(pin); }

  StatusCode restorePageValidation(
      int pageId, long pageGeneration, long schemaId,
      long descriptorHash, int expectedType, TupleBTreePageValidationProof target) {
    return operations.validation().restore(
        pageId, pageGeneration, schemaId, descriptorHash, expectedType, target);
  }
  StatusCode rememberPageValidation(
      int pageId, long pageGeneration, long schemaId,
      long descriptorHash, int pageType, TupleBTreePageValidationProof source) {
    return operations.validation().remember(
        pageId, pageGeneration, schemaId, descriptorHash, pageType, source);
  }
  StatusCode consumeTupleMutationInputValidation(
      int pageId, long pageGeneration, long ownerKeyId,
      long schemaId, long descriptorHash, int pageType,
      TupleBTreePageValidationProof target) {
    return operations.validation().consumeTupleMutationInput(
        pageId, pageGeneration, ownerKeyId,
        schemaId, descriptorHash, pageType, target);
  }
  StatusCode sealTupleMutationValidation(
      int pageId, long pageGeneration, long ownerKeyId,
      long schemaId, long descriptorHash, int pageType,
      TupleBTreePageValidationProof source) {
    return operations.validation().sealTupleMutation(
        pageId, pageGeneration, ownerKeyId,
        schemaId, descriptorHash, pageType, source);
  }

  StatusCode pinOperationPage(
      int pageId, boolean writable, IndexedOperationPage result) {
    return operations.operationPins().pin(
        pageId, writable, IndexedTableLimits.MAX_CHANGED_PAGES, result);
  }
  StatusCode pinTupleOperationPage(
      int pageId, boolean writable, long ownerKeyId, IndexedOperationPage result) {
    if (ownerKeyId <= 0 || !operations.mutationStager().identityMatches(
        pageId, io.riverdb.format.page.PageCodec.PAYLOAD_KIND_TUPLE_BTREE, ownerKeyId)) {
      return setStatus(StatusCode.CORRUPTION);
    }
    return operations.operationPins().pin(
        pageId, writable, state.changedPageCapacity(), result);
  }
  StatusCode pinScalarOperationPage(
      int pageId, boolean writable, IndexedOperationPage result) {
    if (!operations.mutationStager().identityMatches(
        pageId, io.riverdb.format.page.PageCodec.PAYLOAD_KIND_SCALAR_BTREE,
        io.riverdb.format.page.PageCodec.SCALAR_OWNER_KEY_ID)) {
      return setStatus(StatusCode.CORRUPTION);
    }
    return operations.operationPins().pin(
        pageId, writable, state.changedPageCapacity(), result);
  }
  StatusCode pinNewOperationPage(int pageId, IndexedOperationPage result) {
    return operations.operationPins().pinNew(
        pageId, IndexedTableLimits.MAX_CHANGED_PAGES,
        io.riverdb.format.page.PageCodec.PAYLOAD_KIND_SCALAR_BTREE,
        io.riverdb.format.page.PageCodec.SCALAR_OWNER_KEY_ID, result);
  }
  StatusCode pinNewOperationPage(
      int pageId, int payloadKind, long ownerKeyId, IndexedOperationPage result) {
    return operations.operationPins().pinNew(
        pageId, state.changedPageCapacity(), payloadKind, ownerKeyId, result);
  }
  StatusCode releaseOperationPage(IndexedOperationPage page) {
    return operations.operationPins().release(page);
  }

  StatusCode markCurrentChanged(int pageId, long start, long end) {
    return operations.metadata().markCurrentChanged(pageId, start, end);
  }
  StatusCode reidentifyCurrent(int pageId, int payloadKind, long ownerKeyId) {
    return operations.metadata().reidentifyCurrent(pageId, payloadKind, ownerKeyId);
  }
  void markClean(int pageId) { operations.metadata().markClean(pageId); }
  void markRebased(int pageId) { operations.metadata().markRebased(pageId); }

  StatusCode encodeCurrent(
      int pageId, DatabaseIncarnation database, WalGeneration generation,
      long start, long end, CRC32C checksum) {
    return operations.codec().encodeCurrent(pageId, database, generation, start, end, checksum);
  }
  StatusCode encodeStaged(
      int pageId, DatabaseIncarnation database, WalGeneration generation,
      long start, long end, CRC32C checksum) {
    return operations.codec().encodeStaged(pageId, database, generation, start, end, checksum);
  }
  StatusCode readCurrent(DurableFile file, int pageId, long offset, IoResult result) {
    return operations.codec().readCurrent(file, pageId, offset, result);
  }
  StatusCode writeCurrent(DurableFile file, int pageId, long offset, IoResult result) {
    return operations.codec().writeCurrent(file, pageId, offset, result);
  }
  StatusCode validateCurrent(int pageId, PageHeader header, CRC32C checksum) {
    return operations.codec().validateCurrent(pageId, header, checksum);
  }
  StatusCode validateRecord(ByteBuffer source, int offset, PageHeader header, CRC32C checksum) {
    return operations.codec().validateRecord(source, offset, header, checksum);
  }
  void copyStagedToRecord(int pageId, ByteBuffer target, int targetOffset) {
    operations.codec().copyStagedToRecord(pageId, target, targetOffset);
  }
  StatusCode installFromRecord(
      ByteBuffer source, int sourceOffset, int pageId, long start, long end) {
    return operations.codec().installFromRecord(source, sourceOffset, pageId, start, end);
  }

  ByteBuffer currentPayload(int pageId) { return operations.metadata().currentPayload(pageId); }
  ByteBuffer stageExisting(int pageId, int maximumChangedPages) {
    return operations.mutationStager().stageExisting(pageId, maximumChangedPages);
  }
  ByteBuffer operationPayload(int pageId) { return operations.metadata().operationPayload(pageId); }
  ByteBuffer stageNew(int pageId, int maximumChangedPages) {
    return operations.mutationStager().stageNew(
        pageId, maximumChangedPages,
        io.riverdb.format.page.PageCodec.PAYLOAD_KIND_SCALAR_BTREE,
        io.riverdb.format.page.PageCodec.SCALAR_OWNER_KEY_ID);
  }
  ByteBuffer stageNew(
      int pageId, int maximumChangedPages, int payloadKind, long ownerKeyId) {
    return operations.mutationStager().stageNew(pageId, maximumChangedPages, payloadKind, ownerKeyId);
  }
  ByteBuffer stageFreeTuple(int pageId, long ownerKeyId, int maximumChangedPages) {
    return operations.mutationStager().stageFreeTuple(pageId, ownerKeyId, maximumChangedPages);
  }

  StatusCode ensureBuffers(int pageId) { return operations.pinning().ensureBuffers(pageId); }
  StatusCode retainBuffer(int pageId) { return operations.pinning().retainBuffer(pageId); }
  void releaseBuffer(int pageId) { operations.pinning().releaseBuffer(pageId); }
  StatusCode reclaimHistorical(long oldestVisibleCommitSequence) {
    return setStatus(operations.current().reclaimHistorical(oldestVisibleCommitSequence));
  }

  StatusCode beginPreparedBatch() { return setStatus(prepared.begin()); }
  StatusCode beginMemberStagingAdmission(IndexedPreparedLogicalCommit member) {
    return operations.mutationStager().beginMemberAdmission(member);
  }
  boolean memberCapacityPressure() { return operations.mutationStager().memberCapacityPressure(); }
  void endMemberStagingAdmission() { operations.mutationStager().endMemberAdmission(); }
  void rollbackStagedMember() { operations.mutationStager().rollbackStagedMember(); }
  void markCapacityPressure(StatusCode status) {
    operations.mutationStager().markCapacityPressure(status);
  }
  StatusCode freezeChangedPages(int member, long oldestVisibleCommitSequence) {
    StatusCode status = prepared.freeze(this, state, member, oldestVisibleCommitSequence);
    operations.mutationStager().markCapacityPressure(status);
    return setStatus(status);
  }
  StatusCode installPreparedPages(
      long[] commitSequences, int memberCount, long start, long end) {
    return setStatus(prepared.install(this, state, commitSequences, memberCount, start, end));
  }
  StatusCode releasePreparedBatch() { return setStatus(prepared.release(this)); }
  StatusCode transferPreparedBatch(long ownerToken, IndexedCountResult result) {
    return setStatus(prepared.transfer(this, ownerToken, result));
  }
  StatusCode releaseDurabilityChain(long ownerToken, int head) {
    return operations.durability().release(ownerToken, head);
  }
  boolean durabilityFrameAvailable(int slot) { return operations.durability().available(slot); }
  void claimDurabilityFrame(int slot, long ownerToken, int nextSlot) {
    operations.durability().claim(slot, ownerToken, nextSlot);
  }
  void cancelPreparedBatch() { prepared.cancel(this); }

  boolean validPresentPage(int pageId) { return operations.metadata().validPresentPage(pageId); }
  boolean operationPresentPage(int pageId) { return operations.metadata().operationPresentPage(pageId); }
  boolean hasDirtyPages() { return operations.metadata().hasDirtyPages(); }
  boolean addChangedPage(int pageId, int maximum) {
    return operations.metadata().addChangedPage(pageId, maximum);
  }
  void clearStagedFlags() { operations.mutationStager().clearStagedFlags(); }

  ByteBuffer beginVacuumPage(int pageId) { return operations.vacuum().beginPage(pageId); }
  ByteBuffer vacuumPayload(int pageId) { return operations.vacuum().payload(pageId); }
  StatusCode sealVacuumPage(int pageId) { return operations.vacuum().sealPage(pageId); }
  StatusCode publishVacuumPage(int pageId, long start, long end) {
    return operations.vacuum().publishPage(pageId, start, end);
  }
  StatusCode forceVacuumPublication() { return operations.vacuum().forcePublication(); }
  void discardVacuumPages() { operations.vacuum().discardPages(); }

  StatusCode lastStatus() { return lastStatus; }
  StatusCode setStatus(StatusCode status) {
    lastStatus = status;
    return status;
  }
  long nextPageGeneration() {
    if (pageGenerationClock == Long.MAX_VALUE) {
      lastStatus = StatusCode.FENCED;
      return 0;
    }
    return ++pageGenerationClock;
  }

  int changedPageCount() { return operations.metadata().changedPageCount(); }
  int changedPageCapacity() { return operations.metadata().changedPageCapacity(); }
  int changedPageId(int index) { return operations.metadata().changedPageId(index); }
  int highestPageId() { return operations.metadata().highestPageId(); }
  long stagedCopyBytes() { return operations.metadata().stagedCopyBytes(); }
  long recordStart(int pageId) { return operations.metadata().recordStart(pageId); }
  long recordEnd(int pageId) { return operations.metadata().recordEnd(pageId); }
  int payloadKind(int pageId) { return operations.metadata().payloadKind(pageId); }
  long ownerKeyId(int pageId) { return operations.metadata().ownerKeyId(pageId); }

  IndexedPageFrame currentFrame(int pageId, boolean load) {
    return operations.current().currentFrame(pageId, load);
  }
  int reusableCurrentSlot(boolean allowEviction, long oldestVisibleCommitSequence) {
    return operations.current().reusableCurrentSlot(allowEviction, oldestVisibleCommitSequence);
  }
  StatusCode prepareCurrentSlotForReuse(int slot) {
    return operations.current().prepareForReuse(slot);
  }
  IndexedPageFrame frameAt(IndexedPageFrame[] frames, int slot) {
    if (frames == currentFrames) return operations.current().frameAt(slot);
    if (frames == stagingFrames) return operations.staging().frameAt(slot);
    setStatus(StatusCode.INVARIANT_BROKEN);
    return null;
  }
  IndexedPageFrame stagingFrame(int pageId) { return operations.staging().frame(pageId); }
  void releaseStagingFrame(int pageId) { operations.staging().release(pageId); }
  IndexedPageFrame preparedFrame(int pageId) {
    IndexedPageFrame frame = prepared.frame(pageId, currentFrames);
    if (frame != null) operations.current().touch(frame);
    return frame;
  }

  static boolean validPageId(int pageId) {
    return pageId > 0 && pageId <= IndexedTableLimits.MAX_PAGES;
  }
}
