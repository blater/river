package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.wal.local.LocalWal;
import io.riverdb.wal.local.LocalWalForceResult;
import io.riverdb.wal.local.LocalWalGroupAppendResult;
import io.riverdb.wal.local.LocalWalLogicalStream;
import io.riverdb.wal.local.LocalWalReadResult;

/** Streams and applies one atomically decided indexed-vacuum WAL operation. */
final class IndexedVacuumWriter {
  private final LocalWalGroupAppendResult appendResult = new LocalWalGroupAppendResult();
  private final LocalWalForceResult forceResult = new LocalWalForceResult();
  private final LocalWalLogicalStream stream = new LocalWalLogicalStream();
  private final LocalWalReadResult readResult = new LocalWalReadResult();
  private final IndexedCountResult count = new IndexedCountResult();
  private final IndexedVacuumBatch batch;
  private final LocalWal wal;
  private final IndexedTableKernel kernel;
  private final IndexedWalRecovery recovery;
  private boolean appended;
  private boolean decisionDurable;
  private boolean failureFences;

  IndexedVacuumWriter(
      LocalWal localWal,
      IndexedTableKernel tableKernel,
      IndexedWalRecovery walRecovery) {
    wal = localWal;
    kernel = tableKernel;
    recovery = walRecovery;
    batch = new IndexedVacuumBatch(wal, kernel);
  }

  StatusCode commit(
      long transactionId,
      long commitSequence,
      long lastCommitSequence,
      WalGeneration generation,
      IndexedVacuumResult result) {
    appended = false;
    decisionDurable = false;
    failureFences = false;
    long rowsBefore = kernel.rowCount();
    StatusCode status = kernel.indexedEntryCount(count);
    if (!status.isOk()) return status;
    long retainedRows = count.value();
    if (retainedRows > rowsBefore) return StatusCode.CORRUPTION;
    if (retainedRows == rowsBefore) return StatusCode.CONFLICT;
    status = kernel.vacuumChunkCount(count);
    if (!status.isOk()) return status;
    long chunks = count.value();
    if (chunks <= 0 || chunks > Integer.MAX_VALUE) {
      return chunks < 0 ? StatusCode.CORRUPTION : StatusCode.RESOURCE_EXHAUSTED;
    }
    status = wal.beginLogicalStream(
        transactionId,
        IndexedTableStore.WAL_FORMAT_ID,
        IndexedTableStore.WAL_FORMAT_VERSION,
        stream);
    if (status.isOk()) {
      status = streamBatches(
          commitSequence,
          lastCommitSequence,
          generation,
          retainedRows,
          rowsBefore,
          (int) chunks);
    }
    if (!status.isOk()) return fail(status);
    result.set(rowsBefore, retainedRows, commitSequence);
    return StatusCode.OK;
  }

  boolean failureFences() {
    return failureFences;
  }

  long copiedBytes() {
    return batch.copiedBytes();
  }

  private StatusCode streamBatches(
      long commitSequence,
      long lastCommitSequence,
      WalGeneration generation,
      long retainedRows,
      long rowsBefore,
      int chunkCount) {
    int firstChunk = 0;
    long firstRow = 0;
    while (true) {
      int remaining = chunkCount - firstChunk;
      boolean finalBatch = remaining < LocalWal.MAX_PENDING_RECORDS;
      int batchChunks = finalBatch ? remaining : LocalWal.MAX_PENDING_RECORDS;
      StatusCode status = batch.prepare(
          stream,
          retainedRows, rowsBefore, firstRow, firstChunk, batchChunks, chunkCount, finalBatch);
      if (!status.isOk()) return status;
      long batchRows = batch.rows();
      if (batchRows > retainedRows || firstRow > retainedRows - batchRows
          || finalBatch && firstRow != retainedRows - batchRows) {
        return StatusCode.CORRUPTION;
      }
      status = finalBatch
          ? wal.appendLogicalStreamFinal(
              stream, batch.reservation(), commitSequence, appendResult)
          : wal.appendLogicalStreamContinuation(stream, batch.reservation(), appendResult);
      if (!status.isOk()) return status;
      appended = true;
      status = wal.forceLogicalStreamBatch(stream, forceResult);
      if (status.isOk() && finalBatch) decisionDurable = true;
      if (status.isOk()) {
        status = applyForcedBatch(
            appendResult.startOffset(), lastCommitSequence, generation);
      }
      if (!status.isOk()) return status;
      status = wal.releaseLogicalStreamBatch(stream);
      if (!status.isOk()) return status;
      if (finalBatch) return StatusCode.OK;
      firstChunk += batchChunks;
      firstRow += batchRows;
    }
  }

  private StatusCode applyForcedBatch(
      long start,
      long lastCommitSequence,
      WalGeneration generation) {
    long recordStart = start;
    for (int record = 0; record < forceResult.recordCount(); record++) {
      StatusCode status = wal.readForcedRecord(record, readResult);
      if (!status.isOk()) return status;
      status = recovery.applyOperation(
          recordStart, readResult, generation, lastCommitSequence, Long.MAX_VALUE);
      if (!status.isOk()) return status;
      recordStart = readResult.nextOffset();
    }
    return StatusCode.OK;
  }

  private StatusCode fail(StatusCode failure) {
    StatusCode status = batch.cancel(stream, failure);
    StatusCode cancel = recovery.cancelVacuumOperation();
    if (!cancel.isOk() && cancel != StatusCode.CONFLICT) status = cancel;
    if (decisionDurable) {
      failureFences = true;
      if (stream.isActive()) wal.fenceLogicalStream(stream);
      return StatusCode.FENCED;
    }
    if (!stream.isActive()) return status;
    if (appended) {
      failureFences = true;
      wal.fenceLogicalStream(stream);
      return status;
    }
    StatusCode cleanup = wal.cancelLogicalStream(stream);
    return cleanup.isOk() ? status : cleanup;
  }
}
