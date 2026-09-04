package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.tx.Transaction;
import io.riverdb.tx.api.TransactionOutcome;
import java.util.concurrent.locks.LockSupport;

/** Session-owned intrusive request reused for every synchronous group commit. */
final class IndexedGroupCommitRequest {
  final IndexedTransactionSession session;
  final TransactionOutcome outcome = new TransactionOutcome();
  IndexedGroupCommitRequest next;
  Transaction transaction;
  Thread waiter;
  boolean groupable;
  int eligibilityMask;
  private IndexedCommitPath commitPath;
  private IndexedGroupCommitMetrics metrics;
  private volatile long completedTicket;
  private volatile StatusCode status;
  private long ticket;
  private long submittedNanos;
  private volatile long completedNanos;

  IndexedGroupCommitRequest(IndexedTransactionSession owner) { session = owner; }

  long prepare(
      TransactionOutcome result,
      int groupability,
      IndexedGroupCommitMetrics groupMetrics) {
    if (result == null || waiter != null || completedTicket != ticket) return 0;
    long nextTicket = ticket + 1;
    if (nextTicket <= 0) return 0;
    ticket = nextTicket;
    transaction = session.groupTransaction();
    outcome.reset();
    waiter = Thread.currentThread();
    eligibilityMask = groupability;
    groupable = groupability == 0;
    metrics = groupMetrics;
    submittedNanos = System.nanoTime();
    commitPath = null;
    status = null;
    return nextTicket;
  }

  /** Commit-gate interruption does not cancel PREPARED work; the exact outcome is awaited. */
  StatusCode await(long expectedTicket, TransactionOutcome result) {
    boolean interrupted = false;
    while (completedTicket != expectedTicket) {
      LockSupport.park();
      if (Thread.interrupted()) interrupted = true;
    }
    if (commitPath != null) {
      metrics.recordStage(
          commitPath,
          IndexedCommitStage.NOTIFICATION,
          System.nanoTime() - completedNanos);
    }
    if (interrupted) Thread.currentThread().interrupt();
    StatusCode completedStatus = status;
    if (outcome.isAvailable()) {
      result.set(
          outcome.databaseIncarnationHigh(), outcome.databaseIncarnationLow(),
          outcome.transactionId(), outcome.state(), outcome.commitSequence());
    } else {
      result.reset();
    }
    transaction = null;
    waiter = null;
    groupable = false;
    eligibilityMask = 0;
    commitPath = null;
    metrics = null;
    submittedNanos = 0;
    completedNanos = 0;
    status = null;
    return completedStatus;
  }

  StatusCode completeOnce(StatusCode completion) {
    if (completion == null || waiter == null || completedTicket == ticket || status != null) {
      return StatusCode.CONFLICT;
    }
    status = completion;
    completedNanos = System.nanoTime();
    completedTicket = ticket;
    LockSupport.unpark(waiter);
    return StatusCode.OK;
  }

  long submittedNanos() { return submittedNanos; }

  void commitPath(IndexedCommitPath path) { commitPath = path; }
}
