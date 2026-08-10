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

  public IndexedVacuum(TransactionManager transactionManager, IndexedTable indexedTable) {
    manager = transactionManager;
    table = indexedTable;
  }

  public StatusCode run(TransactionOutcome outcome) {
    committedSequence = 0;
    result.reset();
    return manager.commitMaintenance(this, outcome);
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
