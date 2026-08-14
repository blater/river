package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.storage.heap.HeapInsertResult;
import io.riverdb.wal.local.LocalWal;
import io.riverdb.wal.local.LocalWalForceResult;
import io.riverdb.wal.local.LocalWalReadResult;

/** Owns preflight, WAL force, and ordered publication for one bounded commit group. */
final class IndexedPreparedCommitGroup {
  private final LocalWal wal;
  private final IndexedTableKernel kernel;
  private final IndexedWalRecovery recovery;
  private final IndexedStorePhase phase;
  private final IndexedPreparedPreflight preflight;
  private final IndexedPreparedWriteEncoder encoder;
  private final long[] recordStarts = new long[LocalWal.MAX_PENDING_RECORDS];
  private final long[] commitSequences = new long[LocalWal.MAX_PENDING_RECORDS];
  private final long[] transactionIds = new long[LocalWal.MAX_PENDING_RECORDS];
  private final LocalWalForceResult forceResult = new LocalWalForceResult();
  private final LocalWalReadResult readResult = new LocalWalReadResult();
  private long publishedCommitSequence;
  private boolean failed;

  IndexedPreparedCommitGroup(
      LocalWal localWal,
      IndexedTableKernel tableKernel,
      IndexedWalRecovery walRecovery,
      IndexedStorePhase storePhase) {
    wal = localWal;
    kernel = tableKernel;
    recovery = walRecovery;
    phase = storePhase;
    preflight = new IndexedPreparedPreflight(kernel, phase);
    encoder = new IndexedPreparedWriteEncoder(wal, kernel, phase);
  }

  StatusCode begin() {
    if (phase.operationActive() || phase.preparedInsertGroupActive()) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    preflight.reset();
    encoder.reset();
    return phase.beginPreparedPreflight() ? StatusCode.OK : StatusCode.INVARIANT_BROKEN;
  }

  StatusCode preflight(PendingMutationBuffer mutations) {
    return preflight.validate(mutations);
  }

  StatusCode finishPreflight(int transactionCount) {
    if (!phase.preparedInsertGroupActive()
        || phase.preparedInsertEncoding()
        || transactionCount <= 0
        || transactionCount > LocalWal.MAX_PENDING_RECORDS
        || preflight.keyCount() <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return phase.beginPreparedEncoding() ? StatusCode.OK : StatusCode.INVARIANT_BROKEN;
  }

  StatusCode append(
      long transactionId,
      long commitSequence,
      PendingMutationBuffer mutations,
      HeapInsertResult result) {
    int record = encoder.recordCount();
    StatusCode status = encoder.append(transactionId, commitSequence, mutations, result);
    if (status.isOk()) {
      recordStarts[record] = encoder.recordStart();
      commitSequences[record] = commitSequence;
      transactionIds[record] = transactionId;
    }
    return status;
  }

  StatusCode force() {
    if (!phase.preparedInsertGroupActive()
        || !phase.preparedInsertEncoding()
        || phase.preparedInsertForced()
        || encoder.recordCount() <= 0
        || encoder.rowCount() != preflight.keyCount()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = wal.forcePending(forceResult);
    if (!status.isOk()) {
      failed = true;
      return status;
    }
    return phase.markPreparedForced() ? StatusCode.OK : StatusCode.INVARIANT_BROKEN;
  }

  StatusCode publish(WalGeneration generation, long previousCommitSequence) {
    if (!validPublication()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    publishedCommitSequence = previousCommitSequence;
    StatusCode status = StatusCode.OK;
    for (int index = 0; status.isOk() && index < encoder.recordCount(); index++) {
      status = publishRecord(index, generation);
    }
    StatusCode release = wal.releaseForcedBatch();
    if (status.isOk()) {
      status = release;
    }
    if (!status.isOk()) {
      failed = true;
      return status;
    }
    clear();
    return StatusCode.OK;
  }

  private boolean validPublication() {
    return phase.preparedInsertGroupActive()
        && phase.preparedInsertEncoding()
        && phase.preparedInsertForced()
        && encoder.recordCount() > 0;
  }

  private StatusCode publishRecord(int index, WalGeneration generation) {
    StatusCode status = wal.readForcedRecord(index, readResult);
    if (status.isOk() && !matchesExpectedRecord(index)) {
      status = StatusCode.CORRUPTION;
    }
    if (status.isOk()) {
      status = recovery.applyOperation(
          recordStarts[index], readResult, generation, publishedCommitSequence);
    }
    if (status.isOk()) {
      publishedCommitSequence = commitSequences[index];
    }
    return status;
  }

  private boolean matchesExpectedRecord(int index) {
    return readResult.header().transactionId() == transactionIds[index]
        && readResult.header().commitSequence() == commitSequences[index];
  }

  StatusCode cancel() {
    if (!phase.preparedInsertGroupActive() || encoder.recordCount() != 0) {
      return StatusCode.CONFLICT;
    }
    clear();
    return StatusCode.OK;
  }

  private void clear() {
    preflight.reset();
    for (int index = 0; index < encoder.recordCount(); index++) {
      recordStarts[index] = 0;
      commitSequences[index] = 0;
      transactionIds[index] = 0;
    }
    encoder.reset();
    phase.reset();
  }

  long publishedCommitSequence() {
    return publishedCommitSequence;
  }

  long walCopyBytes() {
    return encoder.walCopyBytes();
  }

  boolean failed() {
    return failed || encoder.failed();
  }
}
