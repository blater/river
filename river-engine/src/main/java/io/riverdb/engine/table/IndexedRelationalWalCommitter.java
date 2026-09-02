package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.wal.local.LocalWal;
import io.riverdb.wal.local.LocalWalForceResult;
import io.riverdb.wal.local.LocalWalGroupAppendResult;
import io.riverdb.wal.local.LocalWalGroupReservation;
import io.riverdb.wal.local.LocalWalLogicalStream;

/** Streams bounded continuation batches and retains only the final forced decision batch. */
final class IndexedRelationalWalCommitter {
  private final int[] payloadBytes = new int[LocalWal.MAX_PENDING_RECORDS];
  private final LocalWalGroupReservation reservation = new LocalWalGroupReservation();
  private final LocalWalGroupAppendResult appendResult = new LocalWalGroupAppendResult();
  private final LocalWalForceResult forceResult = new LocalWalForceResult();
  private final LocalWalLogicalStream stream = new LocalWalLogicalStream();
  private final LocalWal wal;
  private IndexedRelationalWalPlan preparedPlan;
  private long preparedCommitSequence;
  private long logicalStart;
  private long logicalEnd;
  private long copiedPayloadBytes;
  private boolean appended;
  private boolean forced;

  IndexedRelationalWalCommitter(LocalWal localWal) { wal = localWal; }

  StatusCode appendAndForce(IndexedRelationalWalPlan plan, long commitSequence) {
    StatusCode status = prepare(plan, commitSequence);
    return status.isOk() ? forcePrepared() : status;
  }

  StatusCode prepare(IndexedRelationalWalPlan plan, long commitSequence) {
    if (forced || preparedPlan != null || wal == null
        || plan == null || !plan.valid() || commitSequence <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    preparedPlan = plan;
    preparedCommitSequence = commitSequence;
    StatusCode status = wal.beginLogicalStream(
        plan.transactionId(),
        IndexedRelationalWalCodec.WAL_FORMAT_ID,
        IndexedRelationalWalCodec.WAL_FORMAT_VERSION,
        stream);
    if (status.isOk()) status = prepareBatch();
    if (!status.isOk()) {
      if (stream.isActive()) wal.cancelLogicalStream(stream);
      preparedPlan = null;
      preparedCommitSequence = 0;
    }
    return status;
  }

  StatusCode forcePrepared() {
    if (preparedPlan == null || forced || appended) return StatusCode.CONFLICT;
    while (true) {
      boolean finalBatch = !preparedPlan.hasMoreBatches();
      StatusCode status = finalBatch
          ? wal.appendLogicalStreamFinal(
              stream, reservation, preparedCommitSequence, appendResult)
          : wal.appendLogicalStreamContinuation(stream, reservation, appendResult);
      if (!status.isOk()) return failStream(status);
      if (!appended) logicalStart = appendResult.startOffset();
      appended = true;
      logicalEnd = appendResult.endOffset();
      status = wal.forceLogicalStreamBatch(stream, forceResult);
      if (!status.isOk()) return failStream(status);
      forced = true;
      if (finalBatch) {
        preparedPlan = null;
        preparedCommitSequence = 0;
        return StatusCode.OK;
      }
      status = wal.releaseLogicalStreamBatch(stream);
      if (!status.isOk()) return failStream(status);
      forced = false;
      status = preparedPlan.prepareNextBatch();
      if (!status.isOk()) return failStream(status);
      status = prepareBatch();
      if (!status.isOk()) return failStream(status);
    }
  }

  StatusCode cancelPrepared() {
    if (preparedPlan == null || appended || forced) return StatusCode.CONFLICT;
    StatusCode status = wal.cancelLogicalStreamBatch(stream, reservation);
    if (status.isOk()) status = wal.cancelLogicalStream(stream);
    if (status.isOk()) {
      preparedPlan = null;
      preparedCommitSequence = 0;
    }
    return status;
  }

  StatusCode releaseForced() {
    if (!forced) return StatusCode.CONFLICT;
    StatusCode status = wal.releaseLogicalStreamBatch(stream);
    if (status.isOk()) {
      forced = false;
      appended = false;
      logicalStart = logicalEnd = 0;
    }
    return status;
  }

  boolean forced() { return forced; }
  boolean appended() { return appended; }
  long recordStart() { return forced ? logicalStart : 0; }
  long recordEnd() { return forced ? logicalEnd : 0; }

  private StatusCode prepareBatch() {
    int chunks = preparedPlan.batchChunkCount();
    for (int chunk = 0; chunk < chunks; chunk++) {
      payloadBytes[chunk] = preparedPlan.payloadBytesAt(chunk);
    }
    StatusCode status = wal.reserveLogicalStreamBatch(
        stream, payloadBytes, chunks, reservation);
    for (int chunk = 0; status.isOk() && chunk < chunks; chunk++) {
      status = IndexedRelationalWalCodec.encode(
          preparedPlan, chunk, reservation.writablePayload(chunk));
      if (status.isOk()) {
        copiedPayloadBytes += IndexedRelationalWalCodec.copiedPayloadBytes(
            preparedPlan, chunk);
      }
    }
    return status.isOk() ? status : cancelReservation(status);
  }

  private StatusCode cancelReservation(StatusCode failure) {
    if (!reservation.isActive()) return failure;
    StatusCode cleanup = wal.cancelLogicalStreamBatch(stream, reservation);
    return cleanup.isOk() ? failure : cleanup;
  }

  private StatusCode failStream(StatusCode failure) {
    if (appended && stream.isActive()) wal.fenceLogicalStream(stream);
    return failure;
  }

  long copiedPayloadBytes() { return copiedPayloadBytes; }
}
