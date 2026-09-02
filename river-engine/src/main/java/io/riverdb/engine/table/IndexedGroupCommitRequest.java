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
  private volatile long completedTicket;
  private volatile StatusCode status;
  private long ticket;

  IndexedGroupCommitRequest(IndexedTransactionSession owner) { session = owner; }

  long prepare(TransactionOutcome result, boolean canGroup) {
    if (result == null || waiter != null || completedTicket != ticket) return 0;
    long nextTicket = ticket + 1;
    if (nextTicket <= 0) return 0;
    ticket = nextTicket;
    transaction = session.groupTransaction();
    outcome.reset();
    waiter = Thread.currentThread();
    groupable = canGroup;
    status = null;
    return nextTicket;
  }

  StatusCode await(long expectedTicket, TransactionOutcome result) {
    boolean interrupted = false;
    while (completedTicket != expectedTicket) {
      LockSupport.park();
      if (Thread.interrupted()) interrupted = true;
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
    status = null;
    return completedStatus;
  }

  void complete(StatusCode completion) {
    status = completion;
    completedTicket = ticket;
    LockSupport.unpark(waiter);
  }
}
