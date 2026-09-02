package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.wal.local.LocalWalReadResult;
import java.nio.ByteBuffer;

/** Retains, applies, and atomically publishes one multi-record vacuum operation. */
final class IndexedVacuumWalRecovery {
  private final IndexedPageSet pages;
  private final IndexedTableKernel kernel;
  private final IndexedStorePhase phase;
  private final IndexedCountResult indexedCount = new IndexedCountResult();
  private long expectedRows;
  private long appliedRows;
  private int expectedChunks;
  private int appliedChunks;
  private long transactionId;
  private long recordStart;
  private long firstJournalSequence;

  IndexedVacuumWalRecovery(
      IndexedPageSet pageSet, IndexedTableKernel table, IndexedStorePhase storePhase) {
    pages = pageSet;
    kernel = table;
    phase = storePhase;
  }

  boolean active() { return phase.vacuumOperationActive(); }

  StatusCode apply(
      int operation, int decision, ByteBuffer payload, long start,
      LocalWalReadResult record, long commitSequence, long publishedCommitSequence) {
    if (operation == IndexedWalCodec.OPERATION_TYPE_VACUUM_CHUNK) {
      return decision == 0 && commitSequence == 0
          ? applyChunk(
              payload, start, record.header().transactionId(),
              record.header().journalSequence())
          : StatusCode.CORRUPTION;
    }
    return decision == 1 ? applyCommit(
        payload, record.nextOffset(), record.header().transactionId(),
        commitSequence, publishedCommitSequence) : StatusCode.CORRUPTION;
  }

  StatusCode cancel() {
    StatusCode status = kernel.cancelVacuumVersions(appliedRows);
    finish();
    return status;
  }

  private StatusCode applyChunk(
      ByteBuffer payload, long start, long transaction, long journalSequence) {
    StatusCode status = IndexedWalCodec.validateVacuumChunk(
        payload, IndexedTableLimits.MAX_ROWS, Integer.MAX_VALUE);
    if (!status.isOk()) return status;
    long retained = IndexedWalCodec.vacuumRetainedRows(payload);
    long first = IndexedWalCodec.vacuumFirstRow(payload);
    int rows = IndexedWalCodec.vacuumRowCount(payload);
    int chunk = IndexedWalCodec.vacuumChunk(payload);
    int chunks = IndexedWalCodec.vacuumChunkCount(payload);
    status = admitChunk(
        chunk, first, retained, chunks, transaction, start, journalSequence);
    int offset = IndexedWalCodec.VACUUM_CHUNK_HEADER_BYTES;
    for (int index = 0; status.isOk() && index < rows; index++) {
      int rowBytes = IndexedWalCodec.vacuumEntryRowBytes(payload, offset);
      status = kernel.applyVacuumEntry(payload, offset, appliedRows + 1L);
      if (status.isOk()) appliedRows++;
      offset += IndexedWalCodec.VACUUM_ENTRY_BYTES + rowBytes;
    }
    if (status.isOk() && offset != payload.limit()) status = StatusCode.CORRUPTION;
    if (!status.isOk()) return cancelFailure(status);
    appliedChunks++;
    return StatusCode.OK;
  }

  private StatusCode admitChunk(
      int chunk, long first, long retained, int chunks, long transaction,
      long start, long journalSequence) {
    if (chunk == 0) {
      return admitFirst(
          first, retained, chunks, transaction, start, journalSequence);
    }
    return !active() || transaction != transactionId || retained != expectedRows
            || chunks != expectedChunks || chunk != appliedChunks || first != appliedRows
        ? StatusCode.CORRUPTION : StatusCode.OK;
  }

  private StatusCode admitFirst(
      long first, long retained, int chunks, long transaction,
      long start, long journalSequence) {
    if (transaction <= 0 || !pages.isPresent(IndexedTableKernel.HEAP_PAGE_ID)) {
      return StatusCode.CORRUPTION;
    }
    StatusCode status = kernel.indexedEntryCount(indexedCount);
    if (!status.isOk()) return status;
    if (first != 0 || active() || indexedCount.value() != retained) {
      return StatusCode.CORRUPTION;
    }
    if (phase.operationActive() || phase.commitGroupActive()) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    pages.resetChanges();
    if (!phase.beginVacuumApply()) return StatusCode.INVARIANT_BROKEN;
    expectedRows = retained;
    expectedChunks = chunks;
    transactionId = transaction;
    recordStart = start;
    firstJournalSequence = journalSequence;
    status = kernel.beginVacuumApply();
    return status.isOk() ? status : cancelFailure(status);
  }

  private StatusCode applyCommit(
      ByteBuffer payload, long end, long transaction,
      long commitSequence, long publishedCommitSequence) {
    StatusCode status = IndexedWalCodec.validateVacuumCommit(
        payload, IndexedTableLimits.MAX_ROWS, Integer.MAX_VALUE);
    if (!status.isOk()) return cancelFailure(status);
    long retained = IndexedWalCodec.vacuumRetainedRows(payload);
    int chunks = IndexedWalCodec.vacuumCommitChunkCount(payload);
    if (!active() || transaction != transactionId || commitSequence <= publishedCommitSequence
        || retained != expectedRows || chunks != expectedChunks
        || appliedRows != retained || appliedChunks != chunks
        || IndexedWalCodec.vacuumCommitRowsBefore(payload) != kernel.rowCount()) {
      return cancelFailure(StatusCode.CORRUPTION);
    }
    status = kernel.finishVacuumApply();
    if (status.isOk()) status = kernel.publishVacuumApply(recordStart, end);
    if (status.isOk()) status = kernel.rebuildRowLocations();
    if (status.isOk() && kernel.rowCount() != retained) status = StatusCode.CORRUPTION;
    if (status.isOk()) status = kernel.publishVacuumVersions(retained, commitSequence);
    if (!status.isOk()) return cancelFailure(status);
    finish();
    return StatusCode.OK;
  }

  private StatusCode cancelFailure(StatusCode failure) {
    StatusCode cleanup = cancel();
    return cleanup.isOk() ? failure : cleanup;
  }

  private void finish() {
    phase.reset();
    pages.resetChanges();
    expectedRows = 0;
    appliedRows = 0;
    expectedChunks = 0;
    appliedChunks = 0;
    kernel.resetVacuumApply();
    transactionId = 0;
    recordStart = 0;
    firstJournalSequence = 0;
  }

  long recordStart() { return recordStart; }
  long firstJournalSequence() { return firstJournalSequence; }
}
