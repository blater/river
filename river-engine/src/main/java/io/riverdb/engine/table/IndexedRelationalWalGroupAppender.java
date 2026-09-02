package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.wal.local.LocalWal;
import io.riverdb.wal.local.LocalWalForceResult;
import io.riverdb.wal.local.LocalWalGroupAppendResult;
import io.riverdb.wal.local.LocalWalGroupReservation;

/** Atomically admits independent relational decisions and forces their shared batch once. */
final class IndexedRelationalWalGroupAppender {
  private final int[] payloadBytes = new int[LocalWal.MAX_PENDING_RECORDS];
  private final int[] groupEnds = new int[LocalWal.MAX_PENDING_RECORDS];
  private final long[] transactionIds = new long[LocalWal.MAX_PENDING_RECORDS];
  private final long[] sequences = new long[LocalWal.MAX_PENDING_RECORDS];
  private final LocalWalGroupReservation reservation = new LocalWalGroupReservation();
  private final LocalWalGroupAppendResult append = new LocalWalGroupAppendResult();
  private final LocalWalForceResult force = new LocalWalForceResult();
  private final LocalWal wal;
  private long copiedPayloadBytes;
  private boolean appended;
  private boolean forced;

  IndexedRelationalWalGroupAppender(LocalWal localWal) { wal = localWal; }

  StatusCode append(
      IndexedRelationalWalPlan[] plans, long[] commitSequences, int count) {
    if (appended || forced || plans == null || commitSequences == null
        || count <= 0 || count > plans.length || count > commitSequences.length) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int records = prepare(plans, commitSequences, count);
    if (records <= 0) return StatusCode.RESOURCE_EXHAUSTED;
    StatusCode status = wal.reserveGroup(payloadBytes, records, reservation);
    int record = 0;
    for (int group = 0; status.isOk() && group < count; group++) {
      IndexedRelationalWalPlan plan = plans[group];
      for (int chunk = 0; status.isOk() && chunk < plan.batchChunkCount(); chunk++) {
        status = IndexedRelationalWalCodec.encode(
            plan, chunk, reservation.writablePayload(record++));
        if (status.isOk()) {
          copiedPayloadBytes += IndexedRelationalWalCodec.copiedPayloadBytes(plan, chunk);
        }
      }
    }
    if (!status.isOk()) return cancel(status);
    status = wal.appendDecisionBatchUnforced(
        reservation, transactionIds, sequences, groupEnds, count,
        IndexedRelationalWalCodec.WAL_FORMAT_ID,
        IndexedRelationalWalCodec.WAL_FORMAT_VERSION, append);
    if (status.isOk()) appended = true;
    return status;
  }

  StatusCode force() {
    if (!appended || forced) return StatusCode.CONFLICT;
    StatusCode status = wal.forcePending(force);
    if (status.isOk()) forced = true;
    return status;
  }

  StatusCode release() {
    if (!forced) return StatusCode.CONFLICT;
    StatusCode status = wal.releaseForcedBatch();
    if (status.isOk()) reset();
    return status;
  }

  StatusCode fence() {
    return appended ? wal.fencePendingBatch() : StatusCode.OK;
  }

  void reset() {
    append.reset();
    appended = false;
    forced = false;
  }

  long start() { return appended ? append.startOffset() : 0; }
  long end() { return appended ? append.endOffset() : 0; }
  boolean appended() { return appended; }
  boolean forced() { return forced; }
  long copiedPayloadBytes() { return copiedPayloadBytes; }

  private int prepare(
      IndexedRelationalWalPlan[] plans, long[] commitSequences, int count) {
    int record = 0;
    for (int group = 0; group < count; group++) {
      IndexedRelationalWalPlan plan = plans[group];
      if (plan == null || !plan.valid() || plan.hasMoreBatches()
          || record > LocalWal.MAX_PENDING_RECORDS - plan.batchChunkCount()) return -1;
      transactionIds[group] = plan.transactionId();
      sequences[group] = commitSequences[group];
      for (int chunk = 0; chunk < plan.batchChunkCount(); chunk++) {
        payloadBytes[record++] = plan.payloadBytesAt(chunk);
      }
      groupEnds[group] = record;
    }
    return record;
  }

  private StatusCode cancel(StatusCode failure) {
    if (reservation.isActive()) {
      StatusCode status = wal.cancelGroup(reservation);
      if (!status.isOk()) return status;
    }
    return failure;
  }
}
