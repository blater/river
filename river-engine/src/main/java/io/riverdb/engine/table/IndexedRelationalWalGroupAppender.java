package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.wal.local.LocalWal;
import io.riverdb.wal.local.LocalWalAppendDisposition;
import io.riverdb.wal.local.LocalWalDecisionBatch;
import io.riverdb.wal.local.LocalWalForceCause;
import io.riverdb.wal.local.LocalWalForceResult;
import io.riverdb.wal.local.LocalWalGroupAppendResult;
import java.nio.ByteBuffer;

/** Streams independent relational decisions and forces their admitted cohort once. */
final class IndexedRelationalWalGroupAppender implements LocalWalDecisionBatch {
  private int[] groupEnds = new int[0];
  private final LocalWalGroupAppendResult append = new LocalWalGroupAppendResult();
  private final LocalWalForceResult force = new LocalWalForceResult();
  private final LocalWal wal;
  private IndexedRelationalWalPlan[] plans;
  private long[] commitSequences;
  private long copiedPayloadBytes;
  private int transactions;
  private int records;
  private boolean appended;
  private boolean forced;

  IndexedRelationalWalGroupAppender(LocalWal localWal) { wal = localWal; }

  StatusCode reserveTransactionCapacity(int required) {
    if (required <= 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (groupEnds.length >= required) return StatusCode.OK;
    try {
      groupEnds = java.util.Arrays.copyOf(groupEnds, required);
      return StatusCode.OK;
    } catch (OutOfMemoryError exhausted) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  StatusCode append(
      IndexedRelationalWalPlan[] preparedPlans, long[] sequences, int count) {
    if (appended || forced || plans != null || preparedPlans == null || sequences == null
        || count <= 0 || count > preparedPlans.length || count > sequences.length) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = prepare(preparedPlans, sequences, count);
    if (status.isOk()) {
      status = wal.appendDecisionBatchUnforced(
          this,
          IndexedRelationalWalCodec.WAL_FORMAT_ID,
          IndexedRelationalWalCodec.WAL_FORMAT_VERSION,
          append);
    }
    if (status.isOk()) {
      for (int transaction = 0; transaction < count; transaction++) {
        copiedPayloadBytes += preparedPlans[transaction].copiedPayloadBytes();
      }
      appended = true;
    }
    clearSource();
    return status;
  }

  StatusCode force(LocalWalForceCause cause) {
    if (!appended || forced || cause == null) return StatusCode.CONFLICT;
    StatusCode status = wal.forcePending(force, cause);
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
    return storageMayHaveChanged() ? wal.fencePendingBatch() : StatusCode.OK;
  }

  void reset() {
    append.reset();
    appended = false;
    forced = false;
  }

  long start() { return appended ? append.startOffset() : 0; }
  long end() { return appended ? append.endOffset() : 0; }
  boolean appended() { return appended; }
  boolean storageMayHaveChanged() {
    return append.disposition() != LocalWalAppendDisposition.NOTHING_WRITTEN;
  }
  boolean forced() { return forced; }
  long copiedPayloadBytes() { return copiedPayloadBytes; }

  @Override
  public int recordCount() { return records; }

  @Override
  public int payloadBytes(int record) {
    int transaction = transactionForRecord(record);
    if (transaction < 0) return -1;
    int first = transaction == 0 ? 0 : groupEnds[transaction - 1];
    return plans[transaction].payloadBytesAt(record - first);
  }

  @Override
  public StatusCode encodePayload(int record, ByteBuffer target) {
    int transaction = transactionForRecord(record);
    if (transaction < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    int first = transaction == 0 ? 0 : groupEnds[transaction - 1];
    return IndexedRelationalWalCodec.encode(plans[transaction], record - first, target);
  }

  @Override
  public int transactionCount() { return transactions; }

  @Override
  public int transactionEndRecord(int transaction) {
    return transaction < 0 || transaction >= transactions ? -1 : groupEnds[transaction];
  }

  @Override
  public long transactionId(int transaction) {
    return transaction < 0 || transaction >= transactions
        ? 0 : plans[transaction].transactionId();
  }

  @Override
  public long commitSequence(int transaction) {
    return transaction < 0 || transaction >= transactions
        ? 0 : commitSequences[transaction];
  }

  private StatusCode prepare(
      IndexedRelationalWalPlan[] preparedPlans, long[] sequences, int count) {
    if (groupEnds.length < count) return StatusCode.RESOURCE_EXHAUSTED;
    int total = 0;
    for (int transaction = 0; transaction < count; transaction++) {
      IndexedRelationalWalPlan plan = preparedPlans[transaction];
      if (plan == null || !plan.valid()
          || total > Integer.MAX_VALUE - plan.recordCount()) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      total += plan.recordCount();
      groupEnds[transaction] = total;
    }
    plans = preparedPlans;
    commitSequences = sequences;
    transactions = count;
    records = total;
    return total > 0 ? StatusCode.OK : StatusCode.INVALID_EXTERNAL_INPUT;
  }

  private int transactionForRecord(int record) {
    if (record < 0 || record >= records) return -1;
    int low = 0;
    int high = transactions - 1;
    while (low < high) {
      int middle = (low + high) >>> 1;
      if (record < groupEnds[middle]) high = middle;
      else low = middle + 1;
    }
    return low;
  }

  private void clearSource() {
    plans = null;
    commitSequences = null;
    transactions = 0;
    records = 0;
  }
}
