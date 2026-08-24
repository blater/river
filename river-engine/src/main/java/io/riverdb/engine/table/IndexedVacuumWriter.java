package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.wal.local.LocalWal;
import io.riverdb.wal.local.LocalWalAppendResult;
import io.riverdb.wal.local.LocalWalForceResult;
import io.riverdb.wal.local.LocalWalReadResult;
import io.riverdb.wal.local.LocalWalReservation;
import java.nio.ByteBuffer;

/** Owns the live multi-record WAL protocol for indexed vacuum publication. */
final class IndexedVacuumWriter {
  private final LocalWal wal;
  private final IndexedTableKernel kernel;
  private final IndexedWalRecovery recovery;
  private final long[] recordStarts = new long[LocalWal.MAX_PENDING_RECORDS];
  private final LocalWalReservation reservation = new LocalWalReservation();
  private final LocalWalAppendResult appendResult = new LocalWalAppendResult();
  private final LocalWalForceResult forceResult = new LocalWalForceResult();
  private final LocalWalReadResult readResult = new LocalWalReadResult();
  private long copiedBytes;
  private boolean failureFences;

  IndexedVacuumWriter(
      LocalWal localWal,
      IndexedTableKernel tableKernel,
      IndexedWalRecovery walRecovery) {
    wal = localWal;
    kernel = tableKernel;
    recovery = walRecovery;
  }

  StatusCode commit(
      long transactionId,
      long commitSequence,
      long lastCommitSequence,
      WalGeneration generation,
      IndexedVacuumResult result) {
    failureFences = false;
    long rowsBefore = kernel.rowCount();
    long retainedRows = kernel.indexedEntryCount();
    if (retainedRows < 0 || retainedRows > rowsBefore) {
      return StatusCode.CORRUPTION;
    }
    if (retainedRows == rowsBefore) {
      return StatusCode.CONFLICT;
    }
    int chunkCount = kernel.vacuumChunkCount();
    StatusCode status = validateChunkCount(chunkCount);
    if (!status.isOk()) {
      return status;
    }
    status = appendChunks(transactionId, retainedRows, chunkCount);
    if (status.isOk()) {
      status = appendCommit(
          transactionId, commitSequence, retainedRows, rowsBefore, chunkCount);
    }
    if (status.isOk()) {
      status = forceAndApply(chunkCount, lastCommitSequence, generation);
    }
    clearRecordStarts();
    if (!status.isOk()) {
      failureFences = true;
      return status;
    }
    result.set(rowsBefore, retainedRows, commitSequence);
    return StatusCode.OK;
  }

  boolean failureFences() {
    return failureFences;
  }

  long copiedBytes() {
    return copiedBytes;
  }

  private static StatusCode validateChunkCount(int chunkCount) {
    if (chunkCount < 0) {
      return StatusCode.CORRUPTION;
    }
    return chunkCount == 0 || chunkCount >= LocalWal.MAX_PENDING_RECORDS
        ? StatusCode.RESOURCE_EXHAUSTED : StatusCode.OK;
  }

  private StatusCode appendChunks(
      long transactionId,
      long retainedRows,
      int chunkCount) {
    long firstRow = 0;
    for (int chunk = 0; chunk < chunkCount; chunk++) {
      int chunkRows = kernel.vacuumChunkRowCount(firstRow);
      int chunkBytes = kernel.vacuumChunkPayloadBytes(firstRow, chunkRows);
      if (chunkRows <= 0 || chunkBytes <= IndexedWalCodec.VACUUM_CHUNK_HEADER_BYTES) {
        return StatusCode.CORRUPTION;
      }
      StatusCode status = appendChunk(
          transactionId, retainedRows, firstRow, chunkRows, chunk, chunkCount, chunkBytes);
      if (!status.isOk()) {
        return status;
      }
      firstRow += chunkRows;
    }
    return firstRow == retainedRows ? StatusCode.OK : StatusCode.CORRUPTION;
  }

  private StatusCode appendChunk(
      long transactionId,
      long retainedRows,
      long firstRow,
      int chunkRows,
      int chunk,
      int chunkCount,
      int chunkBytes) {
    StatusCode status = wal.reserve(chunkBytes, reservation);
    if (!status.isOk()) {
      return status;
    }
    status = encodeChunk(
        reservation.writablePayload(),
        retainedRows,
        firstRow,
        chunkRows,
        chunk,
        chunkCount,
        chunkBytes);
    if (!status.isOk()) {
      wal.cancel(reservation);
      return status;
    }
    status = wal.appendUnforced(
        reservation,
        transactionId,
        0,
        0,
        IndexedTableStore.WAL_FORMAT_ID,
        IndexedTableStore.WAL_FORMAT_VERSION,
        appendResult);
    if (status.isOk()) {
      recordStarts[chunk] = appendResult.startOffset();
    }
    return status;
  }

  private StatusCode appendCommit(
      long transactionId,
      long commitSequence,
      long retainedRows,
      long rowsBefore,
      int chunkCount) {
    StatusCode status = wal.reserve(
        IndexedTableStore.VACUUM_COMMIT_PAYLOAD_BYTES, reservation);
    if (!status.isOk()) {
      return status;
    }
    ByteBuffer payload = reservation.writablePayload();
    IndexedWalCodec.encodeVacuumCommit(payload, retainedRows, chunkCount, rowsBefore);
    payload.position(IndexedTableStore.VACUUM_COMMIT_PAYLOAD_BYTES);
    status = wal.appendUnforced(
        reservation,
        transactionId,
        commitSequence,
        1,
        IndexedTableStore.WAL_FORMAT_ID,
        IndexedTableStore.WAL_FORMAT_VERSION,
        appendResult);
    if (status.isOk()) {
      recordStarts[chunkCount] = appendResult.startOffset();
    }
    return status;
  }

  private StatusCode forceAndApply(
      int chunkCount,
      long lastCommitSequence,
      WalGeneration generation) {
    StatusCode status = wal.forcePending(forceResult);
    if (!status.isOk()) {
      return status;
    }
    for (int record = 0; status.isOk() && record <= chunkCount; record++) {
      status = applyForcedRecord(record, lastCommitSequence, generation);
    }
    StatusCode release = wal.releaseForcedBatch();
    return status.isOk() ? release : status;
  }

  private StatusCode applyForcedRecord(
      int record,
      long lastCommitSequence,
      WalGeneration generation) {
    StatusCode status = wal.readForcedRecord(record, readResult);
    return status.isOk()
        ? recovery.applyOperation(
            recordStarts[record],
            readResult,
            generation,
            lastCommitSequence)
        : status;
  }

  private StatusCode encodeChunk(
      ByteBuffer payload,
      long retainedRows,
      long firstRow,
      int rowLimit,
      int chunk,
      int chunkCount,
      int payloadBytes) {
    StatusCode status = kernel.encodeVacuumChunk(
        payload, retainedRows, firstRow, rowLimit, chunk, chunkCount, payloadBytes);
    if (status.isOk()) {
      copiedBytes += payloadBytes
          - IndexedWalCodec.VACUUM_CHUNK_HEADER_BYTES
          - rowLimit * IndexedWalCodec.VACUUM_ENTRY_BYTES;
    }
    return status;
  }

  private void clearRecordStarts() {
    for (int index = 0; index < recordStarts.length; index++) {
      recordStarts[index] = 0;
    }
  }
}
