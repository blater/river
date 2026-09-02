package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.tx.TransactionCommitParticipant;
import io.riverdb.tx.TransactionManager;
import io.riverdb.tx.api.TransactionOutcome;

/** Reusable synchronous maintenance participant for quiescent version compaction. */
public final class IndexedVacuum implements TransactionCommitParticipant {
  private final TransactionManager manager;
  private final IndexedTable table;
  private final IndexedVacuumResult result = new IndexedVacuumResult();
  private long committedSequence;
  private long automaticRuns;
  private long automaticDeferrals;
  private long automaticPressureRejections;
  private long automaticRowsReclaimed;
  private boolean automaticDeferred;
  private boolean pressureRejected;

  public IndexedVacuum(TransactionManager transactionManager, IndexedTable indexedTable) {
    manager = transactionManager;
    table = indexedTable;
  }

  public synchronized StatusCode run(TransactionOutcome outcome) {
    return runMaintenance(outcome);
  }

  public synchronized StatusCode runAutomatic(TransactionOutcome outcome) {
    return runAutomatic(outcome, false);
  }

  public synchronized StatusCode runAutomatic(
      TransactionOutcome outcome,
      boolean rejectAdmissionWhenDeferred) {
    StatusCode status = table.vacuumPreflight();
    if (status.isOk()) {
      status = runMaintenance(outcome);
    }
    if (status.isOk()) {
      automaticRuns++;
      automaticRowsReclaimed += result.rowsReclaimed();
      automaticDeferred = false;
      pressureRejected = false;
    } else if (status == StatusCode.RETRY || status == StatusCode.RESOURCE_EXHAUSTED) {
      recordAutomaticDeferral(status == StatusCode.RETRY && rejectAdmissionWhenDeferred);
    }
    return status;
  }

  /** Coalesces a known-busy automatic request without performing vacuum preflight. */
  synchronized StatusCode deferAutomatic(boolean rejectAdmission) {
    recordAutomaticDeferral(rejectAdmission);
    return rejectAdmission ? StatusCode.RETRY : StatusCode.OK;
  }

  public synchronized long automaticRuns() {
    return automaticRuns;
  }

  public synchronized long automaticDeferrals() {
    return automaticDeferrals;
  }

  public synchronized long automaticPressureRejections() {
    return automaticPressureRejections;
  }

  public synchronized long automaticRowsReclaimed() {
    return automaticRowsReclaimed;
  }

  private StatusCode runMaintenance(TransactionOutcome outcome) {
    committedSequence = 0;
    result.reset();
    return manager.commitMaintenance(this, outcome);
  }

  private void recordAutomaticDeferral(boolean rejectAdmission) {
    if (!automaticDeferred) {
      automaticDeferred = true;
      automaticDeferrals++;
    }
    if (rejectAdmission && !pressureRejected) {
      pressureRejected = true;
      automaticPressureRejections++;
    }
  }

  public IndexedVacuumResult result() {
    return result;
  }

  @Override
  public StatusCode commit(long transactionId) {
    StatusCode status = table.vacuum(transactionId, result);
    committedSequence = status.isOk() ? result.commitSequence() : 0;
    return status;
  }

  @Override
  public long committedSequence() {
    return committedSequence;
  }
}
