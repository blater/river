package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.wal.local.LocalWal;
import io.riverdb.wal.local.LocalWalForceCause;
import io.riverdb.wal.local.LocalWalForceResult;
import io.riverdb.wal.local.LocalWalGroupAppendResult;
import io.riverdb.wal.local.LocalWalLogicalStream;

/** Appends and forces one complete admitted logical transaction. */
final class IndexedRelationalWalCommitter {
  private final LocalWalGroupAppendResult appendResult = new LocalWalGroupAppendResult();
  private final LocalWalForceResult forceResult = new LocalWalForceResult();
  private final LocalWalLogicalStream stream = new LocalWalLogicalStream();
  private final LocalWal wal;
  private final IndexedGroupCommitMetrics metrics;
  private IndexedRelationalWalPlan preparedPlan;
  private long preparedCommitSequence;
  private long logicalStart;
  private long logicalEnd;
  private long copiedPayloadBytes;
  private boolean appended;
  private boolean forced;

  IndexedRelationalWalCommitter(
      LocalWal localWal, IndexedGroupCommitMetrics commitMetrics) {
    wal = localWal;
    metrics = commitMetrics;
  }

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
    if (!status.isOk()) {
      if (stream.isActive()) wal.cancelLogicalStream(stream);
      preparedPlan = null;
      preparedCommitSequence = 0;
    }
    return status;
  }

  StatusCode forcePrepared() {
    if (preparedPlan == null || forced || appended) return StatusCode.CONFLICT;
    long appendStarted = System.nanoTime();
    StatusCode status = wal.appendLogicalStreamFinal(
        stream, preparedPlan, preparedCommitSequence, appendResult);
    metrics.recordStage(
        IndexedCommitPath.DIRECT_COMMIT,
        IndexedCommitStage.DIRECT_APPEND,
        System.nanoTime() - appendStarted);
    if (!status.isOk()) return failStream(status);
    copiedPayloadBytes += preparedPlan.copiedPayloadBytes();
    logicalStart = appendResult.startOffset();
    appended = true;
    logicalEnd = appendResult.endOffset();
    long forceStarted = System.nanoTime();
    status = wal.forceLogicalStreamBatch(
        stream, forceResult, LocalWalForceCause.DIRECT_COMMIT);
    metrics.recordStage(
        IndexedCommitPath.DIRECT_COMMIT,
        IndexedCommitStage.DIRECT_FORCE,
        System.nanoTime() - forceStarted);
    if (!status.isOk()) return failStream(status);
    forced = true;
    preparedPlan = null;
    preparedCommitSequence = 0;
    return StatusCode.OK;
  }

  StatusCode cancelPrepared() {
    if (preparedPlan == null || appended || forced) return StatusCode.CONFLICT;
    StatusCode status = wal.cancelLogicalStream(stream);
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

  private StatusCode failStream(StatusCode failure) {
    if (appended && stream.isActive()) wal.fenceLogicalStream(stream);
    return failure;
  }

  long copiedPayloadBytes() { return copiedPayloadBytes; }

  IndexedGroupCommitMetrics metrics() { return metrics; }
}
