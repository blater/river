package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.format.page.PageHeader;
import io.riverdb.format.wal.WalFileHeaderCodec;
import io.riverdb.wal.local.LocalWal;
import io.riverdb.wal.local.LocalWalReadResult;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

/** Owns validation and application of indexed WAL records during replay and forced vacuum. */
final class IndexedWalRecovery {
  private static final int HEAP_PAGE_ID = IndexedTableKernel.HEAP_PAGE_ID;
  private static final int MAX_CHANGED_PAGES = IndexedTableLimits.MAX_CHANGED_PAGES;
  private static final int MAX_OPERATION_ROWS = IndexedTableLimits.MAX_OPERATION_ROWS;
  private static final int MAX_ROWS = IndexedTableLimits.MAX_ROWS;

  private final LocalWal wal;
  private final IndexedPageSet pages;
  private final IndexedTableKernel kernel;
  private final DatabaseIncarnation database;
  private final IndexedStorePhase phase;
  private final int[] recoveryPageIds = new int[MAX_CHANGED_PAGES];
  private final CRC32C checksum = new CRC32C();
  private final PageHeader pageHeader = new PageHeader();
  private final LocalWalReadResult walReadResult = new LocalWalReadResult();
  private int vacuumExpectedRows;
  private int vacuumAppliedRows;
  private int vacuumExpectedChunks;
  private int vacuumAppliedChunks;
  private long vacuumTransactionId;
  private long vacuumRecordStart;
  private long recoveredCommitSequence;

  IndexedWalRecovery(
      LocalWal localWal,
      IndexedPageSet pageSet,
      IndexedTableKernel tableKernel,
      DatabaseIncarnation databaseIncarnation,
      IndexedStorePhase storePhase) {
    wal = localWal;
    pages = pageSet;
    kernel = tableKernel;
    database = databaseIncarnation;
    phase = storePhase;
  }

  StatusCode recover(
      WalGeneration generation,
      boolean baseLoaded,
      long initialCommitSequence) {
    recoveredCommitSequence = initialCommitSequence;
    long offset = WalFileHeaderCodec.HEADER_BYTES;
    boolean found = false;
    while (offset < wal.tailEnd()) {
      StatusCode status = wal.read(offset, walReadResult);
      if (!status.isOk()) {
        return status;
      }
      if (currentWalRecord(walReadResult)) {
        status = applyRecoveredRecord(offset, walReadResult, generation);
        if (!status.isOk()) {
          return status;
        }
        if (walReadResult.header().decisionCode() == 1) {
          recoveredCommitSequence = walReadResult.header().commitSequence();
          found = true;
        }
      }
      offset = walReadResult.nextOffset();
    }
    if (phase.vacuumOperationActive()) {
      cancelVacuumOperation();
    }
    return found || baseLoaded ? StatusCode.OK : StatusCode.CORRUPTION;
  }

  long recoveredCommitSequence() {
    return recoveredCommitSequence;
  }

  StatusCode applyOperation(
      long recordStart,
      LocalWalReadResult record,
      WalGeneration generation,
      long publishedCommitSequence) {
    ByteBuffer payload = record.payload();
    if (record.header().payloadBytes() < IndexedWalCodec.PAGE_OPERATION_HEADER_BYTES
        || !IndexedWalCodec.hasCommonHeader(payload)) {
      return StatusCode.CORRUPTION;
    }
    int operationType = IndexedWalCodec.operationType(payload);
    if (phase.vacuumOperationActive()
        && operationType != IndexedWalCodec.OPERATION_TYPE_VACUUM_CHUNK
        && operationType != IndexedWalCodec.OPERATION_TYPE_VACUUM_COMMIT) {
      return StatusCode.CORRUPTION;
    }
    int decisionCode = record.header().decisionCode();
    long commitSequence = record.header().commitSequence();
    if (operationType == IndexedWalCodec.OPERATION_TYPE_VACUUM_CHUNK
        || operationType == IndexedWalCodec.OPERATION_TYPE_VACUUM_COMMIT) {
      return applyVacuumRecord(
          operationType,
          decisionCode,
          payload,
          recordStart,
          record,
          commitSequence,
          publishedCommitSequence);
    }
    if (decisionCode != 1) {
      return StatusCode.CORRUPTION;
    }
    if (operationType == IndexedWalCodec.OPERATION_TYPE_INSERT) {
      return kernel.applyInsertOperation(
          payload, recordStart, record.nextOffset(), commitSequence);
    }
    if (operationType == IndexedWalCodec.OPERATION_TYPE_INSERT_BATCH) {
      return kernel.applyInsertBatchOperation(
          payload, recordStart, record.nextOffset(), commitSequence);
    }
    if (operationType == IndexedWalCodec.OPERATION_TYPE_MUTATION_BATCH) {
      return kernel.applyMutationBatchOperation(
          payload, recordStart, record.nextOffset(), commitSequence);
    }
    if (operationType != IndexedWalCodec.OPERATION_TYPE_PAGE_IMAGES) {
      return StatusCode.CORRUPTION;
    }
    return applyPageOperation(
        payload,
        recordStart,
        record.nextOffset(),
        record.header().payloadBytes(),
        commitSequence,
        generation);
  }

  void cancelVacuumOperation() {
    pages.clearStagedFlags();
    kernel.cancelVacuumVersions(vacuumAppliedRows);
    finishVacuumOperation();
  }

  private StatusCode applyRecoveredRecord(
      long offset,
      LocalWalReadResult record,
      WalGeneration generation) {
    int decisionCode = record.header().decisionCode();
    long commitSequence = record.header().commitSequence();
    if (decisionCode != 0 && decisionCode != 1) {
      return StatusCode.CORRUPTION;
    }
    if (decisionCode == 1 && commitSequence <= recoveredCommitSequence) {
      return StatusCode.CORRUPTION;
    }
    return applyOperation(
        offset, record, generation, recoveredCommitSequence);
  }

  private static boolean currentWalRecord(LocalWalReadResult record) {
    return record.header().formatId() == IndexedTableStore.WAL_FORMAT_ID
        && record.header().formatVersion() == IndexedTableStore.WAL_FORMAT_VERSION;
  }

  private StatusCode applyVacuumRecord(
      int operationType,
      int decisionCode,
      ByteBuffer payload,
      long recordStart,
      LocalWalReadResult record,
      long commitSequence,
      long publishedCommitSequence) {
    if (operationType == IndexedWalCodec.OPERATION_TYPE_VACUUM_CHUNK) {
      return decisionCode == 0 && commitSequence == 0
          ? applyVacuumChunk(payload, recordStart, record.header().transactionId())
          : StatusCode.CORRUPTION;
    }
    return decisionCode == 1
        ? applyVacuumCommit(
            payload,
            record.nextOffset(),
            record.header().transactionId(),
            commitSequence,
            publishedCommitSequence)
        : StatusCode.CORRUPTION;
  }

  private StatusCode applyPageOperation(
      ByteBuffer payload,
      long recordStart,
      long recordEnd,
      int payloadBytes,
      long commitSequence,
      WalGeneration generation) {
    StatusCode structural = IndexedWalCodec.validatePageOperation(
        payload, MAX_CHANGED_PAGES, MAX_OPERATION_ROWS);
    if (!structural.isOk()) {
      return structural;
    }
    int pageCount = IndexedWalCodec.pageOperationPageCount(payload);
    int versionCount = IndexedWalCodec.pageOperationVersionCount(payload);
    int previousRowCount = kernel.rowCount();
    for (int index = 0; index < pageCount; index++) {
      int pageOffset = IndexedWalCodec.pageOperationPageOffset(index);
      StatusCode status = pages.validateRecord(payload, pageOffset, pageHeader, checksum);
      int pageId = (int) pageHeader.pageId();
      if (!status.isOk()
          || pageId <= 0
          || pageId > IndexedTableLimits.MAX_PAGES
          || pageHeader.pageGeneration() != 1
          || pageHeader.databaseHigh() != database.high()
          || pageHeader.databaseLow() != database.low()
          || pageHeader.walGeneration() != generation.value()
          || pageHeader.recordStart() != recordStart
          || pageHeader.recordEnd() != recordEnd
          || IndexedWalCodec.containsEarlierPageId(recoveryPageIds, index, pageId)) {
        return status.isOk() ? StatusCode.CORRUPTION : status;
      }
      recoveryPageIds[index] = pageId;
    }
    for (int index = 0; index < pageCount; index++) {
      int pageId = recoveryPageIds[index];
      int pageOffset = IndexedWalCodec.pageOperationPageOffset(index);
      pages.ensureBuffers(pageId);
      pages.installFromRecord(payload, pageOffset, pageId, recordStart, recordEnd);
    }
    StatusCode status = kernel.validateAppliedPages(recoveryPageIds, pageCount);
    if (status.isOk()) {
      status = kernel.rebuildRowLocations();
    }
    if (status.isOk() && kernel.rowCount() - previousRowCount != versionCount) {
      status = StatusCode.CORRUPTION;
    }
    if (status.isOk()) {
      int versionOffset = IndexedWalCodec.pageOperationVersionsOffset(pageCount);
      status = kernel.applyRecoveredVersions(
          payload, versionOffset, previousRowCount, versionCount, commitSequence);
    }
    return status;
  }

  private StatusCode applyVacuumChunk(
      ByteBuffer payload,
      long recordStart,
      long transactionId) {
    StatusCode structural = IndexedWalCodec.validateVacuumChunk(
        payload, MAX_ROWS, LocalWal.MAX_PENDING_RECORDS - 1);
    if (!structural.isOk()) {
      return structural;
    }
    if (transactionId <= 0 || !pages.isPresent(HEAP_PAGE_ID)) {
      return StatusCode.CORRUPTION;
    }
    int retainedRows = IndexedWalCodec.vacuumRetainedRows(payload);
    int firstRow = IndexedWalCodec.vacuumFirstRow(payload);
    int chunkRows = IndexedWalCodec.vacuumRowCount(payload);
    int chunk = IndexedWalCodec.vacuumChunk(payload);
    int chunkCount = IndexedWalCodec.vacuumChunkCount(payload);
    StatusCode status = admitVacuumChunk(
        chunk, firstRow, retainedRows, chunkCount, transactionId, recordStart);
    int entryOffset = IndexedWalCodec.VACUUM_CHUNK_HEADER_BYTES;
    for (int index = 0; status.isOk() && index < chunkRows; index++) {
      int rowBytes = IndexedWalCodec.vacuumEntryRowBytes(payload, entryOffset);
      status = kernel.applyVacuumEntry(payload, entryOffset, vacuumAppliedRows + 1);
      if (status.isOk()) {
        vacuumAppliedRows++;
      }
      entryOffset += IndexedWalCodec.VACUUM_ENTRY_BYTES + rowBytes;
    }
    if (status.isOk() && entryOffset != payload.limit()) {
      status = StatusCode.CORRUPTION;
    }
    if (!status.isOk()) {
      cancelVacuumOperation();
      return status;
    }
    vacuumAppliedChunks++;
    return StatusCode.OK;
  }

  private StatusCode admitVacuumChunk(
      int chunk,
      int firstRow,
      int retainedRows,
      int chunkCount,
      long transactionId,
      long recordStart) {
    if (chunk == 0) {
      if (firstRow != 0
          || phase.vacuumOperationActive()
          || kernel.indexedEntryCount() != retainedRows) {
        return StatusCode.CORRUPTION;
      }
      return beginVacuumOperation(
          retainedRows, chunkCount, transactionId, recordStart);
    }
    return !phase.vacuumOperationActive()
            || transactionId != vacuumTransactionId
            || retainedRows != vacuumExpectedRows
            || chunkCount != vacuumExpectedChunks
            || chunk != vacuumAppliedChunks
            || firstRow != vacuumAppliedRows
        ? StatusCode.CORRUPTION : StatusCode.OK;
  }

  private StatusCode beginVacuumOperation(
      int retainedRows,
      int chunkCount,
      long transactionId,
      long recordStart) {
    if (phase.operationActive() || phase.preparedInsertGroupActive()) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    pages.resetChanges();
    if (!phase.beginVacuumApply()) {
      return StatusCode.INVARIANT_BROKEN;
    }
    vacuumExpectedRows = retainedRows;
    vacuumExpectedChunks = chunkCount;
    vacuumTransactionId = transactionId;
    vacuumRecordStart = recordStart;
    StatusCode status = kernel.beginVacuumApply();
    if (!status.isOk()) {
      cancelVacuumOperation();
    }
    return status;
  }

  private StatusCode applyVacuumCommit(
      ByteBuffer payload,
      long recordEnd,
      long transactionId,
      long commitSequence,
      long publishedCommitSequence) {
    StatusCode structural = IndexedWalCodec.validateVacuumCommit(
        payload, MAX_ROWS, LocalWal.MAX_PENDING_RECORDS - 1);
    if (!structural.isOk()) {
      cancelVacuumOperation();
      return structural;
    }
    int retainedRows = IndexedWalCodec.vacuumRetainedRows(payload);
    int chunkCount = IndexedWalCodec.vacuumCommitChunkCount(payload);
    int rowsBefore = IndexedWalCodec.vacuumCommitRowsBefore(payload);
    if (!phase.vacuumOperationActive()
        || transactionId != vacuumTransactionId
        || commitSequence <= publishedCommitSequence
        || retainedRows != vacuumExpectedRows
        || chunkCount != vacuumExpectedChunks
        || vacuumAppliedRows != retainedRows
        || vacuumAppliedChunks != chunkCount
        || rowsBefore != kernel.rowCount()) {
      cancelVacuumOperation();
      return StatusCode.CORRUPTION;
    }
    pages.publish(vacuumRecordStart, recordEnd);
    StatusCode status = kernel.rebuildRowLocations();
    if (!status.isOk() || kernel.rowCount() != retainedRows) {
      cancelVacuumOperation();
      return status.isOk() ? StatusCode.CORRUPTION : status;
    }
    kernel.publishVacuumVersions(retainedRows, commitSequence);
    finishVacuumOperation();
    return StatusCode.OK;
  }

  private void finishVacuumOperation() {
    phase.reset();
    pages.resetChanges();
    vacuumExpectedRows = 0;
    vacuumAppliedRows = 0;
    vacuumExpectedChunks = 0;
    vacuumAppliedChunks = 0;
    kernel.resetVacuumApply();
    vacuumTransactionId = 0;
    vacuumRecordStart = 0;
  }
}
