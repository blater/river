package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.runtime.DatabasePageCachePlan;
import io.riverdb.engine.runtime.DatabaseResourcePlan;
import io.riverdb.format.page.PageHeader;
import io.riverdb.format.btree.TupleBTreePageValidationProof;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.IoResult;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

/** Owns page publication metadata and delegates bounded frame storage. */
final class IndexedPageSet {
  private final IndexedPageState state;
  private final IndexedPageFrameCache cache;

  IndexedPageSet(
      DurableFile durableFile,
      DurableFile durableStagingFile,
      DatabaseIncarnation database,
      WalGeneration generation,
      DatabasePageCachePlan config) {
    state = new IndexedPageState(config);
    cache = new IndexedPageFrameCache(
        durableFile, durableStagingFile, database, generation, state, config);
  }

  void setGeneration(WalGeneration generation) { cache.setGeneration(generation); }
  void beginPageImageOperation() { state.beginPageImageOperation(); }

  IndexedTableKernel createKernel(
      DurableFile rowFile,
      DurableFile versionFile,
      DatabaseResourcePlan resourcePlan) {
    return new IndexedTableKernel(
        this,
        new IndexedVersionState(
            new IndexedRowDirectory(rowFile), new IndexedVersionDirectory(versionFile),
            resourcePlan));
  }

  ByteBuffer currentPayloadUnchecked(int pageId) { return cache.currentPayloadUnchecked(pageId); }
  boolean isPresent(int pageId) { return state.present(pageId); }
  boolean isStaged(int pageId) { return state.staged(pageId); }
  boolean isDirty(int pageId) { return state.dirty(pageId); }
  long recordStart(int pageId) { return cache.recordStart(pageId); }
  long recordEnd(int pageId) { return cache.recordEnd(pageId); }
  int changedPageCount() { return state.changedPageCount(); }
  int changedPageCapacity() { return cache.changedPageCapacity(); }
  int changedPageId(int index) { return state.changedPageId(index); }
  int highestPageId() { return state.highestPageId(); }
  int payloadKind(int pageId) { return cache.payloadKind(pageId); }
  long ownerKeyId(int pageId) { return cache.ownerKeyId(pageId); }
  long stagedCopyBytes() { return state.stagedCopyBytes(); }
  StatusCode lastStatus() { return cache.lastStatus(); }
  StatusCode pinCurrentPage(int pageId) { return cache.pinCurrentPage(pageId); }
  void unpinCurrentPage(int pageId) { cache.unpinCurrentPage(pageId); }
  StatusCode pinPageAt(
      int pageId, long visibleCommitSequence, IndexedPageGenerationPin result) {
    return cache.pinPageAt(pageId, visibleCommitSequence, result);
  }
  StatusCode unpinPage(IndexedPageGenerationPin pin) { return cache.unpinPage(pin); }
  StatusCode restorePageValidation(
      int pageId, long pageGeneration, long schemaId,
      long descriptorHash, int expectedType,
      TupleBTreePageValidationProof target) {
    return cache.restorePageValidation(
        pageId, pageGeneration, schemaId, descriptorHash, expectedType, target);
  }
  StatusCode rememberPageValidation(
      int pageId, long pageGeneration, long schemaId,
      long descriptorHash, int pageType,
      TupleBTreePageValidationProof source) {
    return cache.rememberPageValidation(
        pageId, pageGeneration, schemaId, descriptorHash, pageType, source);
  }
  StatusCode consumeTupleMutationInputValidation(
      int pageId, long pageGeneration, long ownerKeyId,
      long schemaId, long descriptorHash, int pageType,
      TupleBTreePageValidationProof target) {
    return cache.consumeTupleMutationInputValidation(
        pageId, pageGeneration, ownerKeyId,
        schemaId, descriptorHash, pageType, target);
  }
  StatusCode sealTupleMutationValidation(
      int pageId, long pageGeneration, long ownerKeyId,
      long schemaId, long descriptorHash, int pageType,
      TupleBTreePageValidationProof source) {
    return cache.sealTupleMutationValidation(
        pageId, pageGeneration, ownerKeyId,
        schemaId, descriptorHash, pageType, source);
  }
  StatusCode pinOperationPage(
      int pageId, boolean writable, IndexedOperationPage result) {
    return cache.pinOperationPage(pageId, writable, result);
  }
  StatusCode pinTupleOperationPage(
      int pageId, boolean writable, long ownerKeyId, IndexedOperationPage result) {
    return cache.pinTupleOperationPage(pageId, writable, ownerKeyId, result);
  }
  StatusCode pinScalarOperationPage(
      int pageId, boolean writable, IndexedOperationPage result) {
    return cache.pinScalarOperationPage(pageId, writable, result);
  }
  StatusCode pinNewOperationPage(int pageId, IndexedOperationPage result) {
    return cache.pinNewOperationPage(pageId, result);
  }
  StatusCode pinNewScalarOperationPage(int pageId, IndexedOperationPage result) {
    return cache.pinNewOperationPage(
        pageId, io.riverdb.format.page.PageCodec.PAYLOAD_KIND_SCALAR_BTREE,
        io.riverdb.format.page.PageCodec.SCALAR_OWNER_KEY_ID, result);
  }
  StatusCode pinNewTupleOperationPage(
      int pageId, long ownerKeyId, IndexedOperationPage result) {
    return cache.pinNewOperationPage(
        pageId, io.riverdb.format.page.PageCodec.PAYLOAD_KIND_TUPLE_BTREE,
        ownerKeyId, result);
  }
  StatusCode releaseOperationPage(IndexedOperationPage page) {
    return cache.releaseOperationPage(page);
  }
  void resetChanges() { state.resetChanges(); }
  StatusCode beginPreparedBatch() { return cache.beginPreparedBatch(); }
  StatusCode freezeChangedPages(int member, long oldestVisibleCommitSequence) {
    return cache.freezeChangedPages(member, oldestVisibleCommitSequence);
  }
  StatusCode installPreparedPages(
      long[] commitSequences, int memberCount, long start, long end) {
    return cache.installPreparedPages(commitSequences, memberCount, start, end);
  }
  StatusCode releasePreparedBatch() { return cache.releasePreparedBatch(); }
  void cancelPreparedBatch() { cache.cancelPreparedBatch(); }

  StatusCode markCurrentChanged(int pageId, long start, long end) {
    return cache.markCurrentChanged(pageId, start, end);
  }

  StatusCode reidentifyCurrent(int pageId, int payloadKind, long ownerKeyId) {
    return cache.reidentifyCurrent(pageId, payloadKind, ownerKeyId);
  }

  StatusCode installPresent(int pageId) { return state.installPresent(pageId); }

  StatusCode installChanged(int pageId, long start, long end) {
    StatusCode status = state.reservePublication(pageId);
    if (status.isOk()) status = state.installPresent(pageId);
    return status.isOk() ? cache.markCurrentChanged(pageId, start, end) : status;
  }

  StatusCode reservePublication(int pageId) { return state.reservePublication(pageId); }

  void markClean(int pageId) { cache.markClean(pageId); }
  void markRebased(int pageId) { cache.markRebased(pageId); }

  StatusCode encodeCurrent(
      int pageId, DatabaseIncarnation database, WalGeneration generation,
      long start, long end, CRC32C checksum) {
    return cache.encodeCurrent(pageId, database, generation, start, end, checksum);
  }

  StatusCode encodeStaged(
      int pageId, DatabaseIncarnation database, WalGeneration generation,
      long start, long end, CRC32C checksum) {
    return cache.encodeStaged(pageId, database, generation, start, end, checksum);
  }

  StatusCode readCurrent(DurableFile file, int pageId, long offset, IoResult result) {
    return cache.readCurrent(file, pageId, offset, result);
  }

  StatusCode writeCurrent(DurableFile file, int pageId, long offset, IoResult result) {
    return cache.writeCurrent(file, pageId, offset, result);
  }

  StatusCode validateCurrent(int pageId, PageHeader header, CRC32C checksum) {
    return cache.validateCurrent(pageId, header, checksum);
  }

  StatusCode validateRecord(
      ByteBuffer source, int sourceOffset, PageHeader header, CRC32C checksum) {
    return cache.validateRecord(source, sourceOffset, header, checksum);
  }

  void copyStagedToRecord(int pageId, ByteBuffer target, int targetOffset) {
    cache.copyStagedToRecord(pageId, target, targetOffset);
  }

  StatusCode installFromRecord(
      ByteBuffer source, int sourceOffset, int pageId, long start, long end) {
    return cache.installFromRecord(source, sourceOffset, pageId, start, end);
  }

  ByteBuffer currentPayload(int pageId) { return cache.currentPayload(pageId); }
  ByteBuffer stageExisting(int pageId, int maximumChangedPages) {
    return cache.stageExisting(pageId, maximumChangedPages);
  }
  ByteBuffer operationPayload(int pageId) { return cache.operationPayload(pageId); }
  ByteBuffer stageNew(int pageId, int maximumChangedPages) {
    return cache.stageNew(pageId, maximumChangedPages);
  }
  ByteBuffer stageNew(
      int pageId, int maximumChangedPages, int payloadKind, long ownerKeyId) {
    return cache.stageNew(pageId, maximumChangedPages, payloadKind, ownerKeyId);
  }
  ByteBuffer stageFreeTuple(int pageId, long ownerKeyId, int maximumChangedPages) {
    return cache.stageFreeTuple(pageId, ownerKeyId, maximumChangedPages);
  }
  StatusCode ensureBuffers(int pageId) { return cache.ensureBuffers(pageId); }
  StatusCode retainBuffer(int pageId) { return cache.retainBuffer(pageId); }
  void releaseBuffer(int pageId) { cache.releaseBuffer(pageId); }
  StatusCode reclaimHistorical(long oldestVisibleCommitSequence) {
    return cache.reclaimHistorical(oldestVisibleCommitSequence);
  }
  boolean validPresentPage(int pageId) { return cache.validPresentPage(pageId); }
  boolean operationPresentPage(int pageId) { return cache.operationPresentPage(pageId); }
  boolean hasDirtyPages() { return cache.hasDirtyPages(); }
  int activePageMetadataCount() { return state.metadataEntryCount(); }
  int activePageMetadataCapacity() { return state.metadataCapacity(); }
  boolean addChangedPage(int pageId, int maximumChangedPages) {
    return cache.addChangedPage(pageId, maximumChangedPages);
  }
  void clearStagedFlags() { cache.clearStagedFlags(); }
  StatusCode closeStagingFile() { return StatusCode.OK; }
  StatusCode detach() {
    StatusCode status = cache.detach();
    return status.isOk() ? state.detach() : status;
  }
  void abandon() {
    cache.abandon();
    state.abandon();
  }
  ByteBuffer beginVacuumPage(int pageId) { return cache.beginVacuumPage(pageId); }
  ByteBuffer vacuumPayload(int pageId) { return cache.vacuumPayload(pageId); }
  StatusCode sealVacuumPage(int pageId) { return cache.sealVacuumPage(pageId); }
  StatusCode publishVacuumPage(int pageId, long start, long end) {
    return cache.publishVacuumPage(pageId, start, end);
  }
  StatusCode forceVacuumPublication() { return cache.forceVacuumPublication(); }
  void discardVacuumPages() { cache.discardVacuumPages(); }

  static void copyPage(ByteBuffer source, ByteBuffer target) {
    target.put(0, source, 0, source.limit());
    target.position(0);
    target.limit(source.limit());
  }
}
