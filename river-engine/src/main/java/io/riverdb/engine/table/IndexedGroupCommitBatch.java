package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.tx.Transaction;
import io.riverdb.tx.TransactionManager;
import io.riverdb.tx.api.TransactionOutcome;
import io.riverdb.wal.local.LocalWal;

/** Reusable physical-format-sized workspace for one commit-writer cohort. */
final class IndexedGroupCommitBatch {
  private final IndexedGroupCommitRequest[] requests =
      new IndexedGroupCommitRequest[LocalWal.MAX_PENDING_RECORDS];
  private final IndexedTransactionSession[] sessions =
      new IndexedTransactionSession[LocalWal.MAX_PENDING_RECORDS];
  private final Transaction[] transactions = new Transaction[LocalWal.MAX_PENDING_RECORDS];
  private final TransactionOutcome[] outcomes =
      new TransactionOutcome[LocalWal.MAX_PENDING_RECORDS];
  private final StatusCode[] statuses = new StatusCode[LocalWal.MAX_PENDING_RECORDS];
  private final long[] commitSequences = new long[LocalWal.MAX_PENDING_RECORDS];
  private final TransactionManager manager;
  private final IndexedTable table;
  private final IndexedGroupCommitMetrics metrics;

  IndexedGroupCommitBatch(
      TransactionManager transactionManager,
      IndexedTable indexedTable,
      IndexedGroupCommitMetrics groupMetrics) {
    manager = transactionManager;
    table = indexedTable;
    metrics = groupMetrics;
  }

  int capacity() { return requests.length; }

  void add(int index, IndexedGroupCommitRequest request) {
    requests[index] = request;
    sessions[index] = request.session;
    transactions[index] = request.transaction;
    outcomes[index] = request.outcome;
  }

  void process(int count) {
    metrics.recordCohort(count);
    if (!requests[0].groupable) {
      commitDirectly(count);
      return;
    }
    StatusCode status = table.preflightHybridCommitGroup(
        sessions, count, manager.oldestVisibleCommitSequence());
    if (!status.isOk()) {
      commitDirectly(count);
      return;
    }
    status = manager.beginCommitGroup(transactions, count);
    if (!status.isOk()) {
      table.cancelCommitGroup();
      commitDirectly(count);
      return;
    }
    status = table.appendHybridCommitGroup(sessions, commitSequences, count);
    if (status.isOk()) status = force();
    if (!status.isOk()) {
      failGroup(count, status);
      return;
    }
    status = manager.publishCommitGroup(
        transactions, outcomes, commitSequences, count, table);
    if (status.isOk()) {
      metrics.shared += count;
    } else {
      table.cancelCommitGroup();
    }
    setAll(count, status);
  }

  void complete(int count) {
    for (int index = 0; index < count; index++) {
      IndexedGroupCommitRequest request = requests[index];
      StatusCode status = statuses[index];
      requests[index] = null;
      sessions[index] = null;
      transactions[index] = null;
      outcomes[index] = null;
      statuses[index] = null;
      commitSequences[index] = 0;
      request.complete(status);
    }
  }

  private StatusCode force() {
    long started = System.nanoTime();
    metrics.forces++;
    StatusCode status = table.forceHybridCommitGroup();
    metrics.recordForce(System.nanoTime() - started);
    return status.isOk() ? table.prepareForcedGroupPublication() : status;
  }

  private void failGroup(int count, StatusCode status) {
    boolean decisionAppended = table.commitGroupDecisionAppended();
    table.cancelCommitGroup();
    if (decisionAppended) {
      manager.failForcedCommitGroup(transactions, outcomes, count, status);
    } else {
      manager.failCommitGroup(transactions, outcomes, count, status);
    }
    setAll(count, status);
  }

  private void commitDirectly(int count) {
    metrics.directFallbacks += count;
    for (int index = 0; index < count; index++) {
      statuses[index] = sessions[index].commitDirect(outcomes[index]);
    }
  }

  private void setAll(int count, StatusCode status) {
    for (int index = 0; index < count; index++) statuses[index] = status;
  }
}
