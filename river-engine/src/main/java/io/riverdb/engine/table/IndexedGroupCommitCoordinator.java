package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.tx.Transaction;
import io.riverdb.tx.TransactionManager;
import io.riverdb.tx.api.TransactionOutcome;
import io.riverdb.wal.local.LocalWal;
import java.util.concurrent.locks.LockSupport;

/** Fixed-capacity insert commit cohort sharing one local WAL force. */
public final class IndexedGroupCommitCoordinator {
  private static final long DEFAULT_GROUP_DELAY_NANOS = 200_000;

  private final TransactionManager manager;
  private final IndexedTable table;
  private final long groupDelayNanos;
  private final IndexedTransactionSession[] sessions =
      new IndexedTransactionSession[LocalWal.MAX_PENDING_RECORDS];
  private final Transaction[] transactions = new Transaction[LocalWal.MAX_PENDING_RECORDS];
  private final TransactionOutcome[] outcomes =
      new TransactionOutcome[LocalWal.MAX_PENDING_RECORDS];
  private final StatusCode[] statuses = new StatusCode[LocalWal.MAX_PENDING_RECORDS];
  private final long[] commitSequences = new long[LocalWal.MAX_PENDING_RECORDS];
  private volatile long completedBatch;
  private long nextBatch = 1;
  private long activeBatch;
  private Thread leaderThread;
  private int requestCount;
  private int remainingReaders;
  private boolean batchActive;
  private volatile boolean accepting;

  public IndexedGroupCommitCoordinator(
      TransactionManager transactionManager,
      IndexedTable indexedTable) {
    this(transactionManager, indexedTable, DEFAULT_GROUP_DELAY_NANOS);
  }

  IndexedGroupCommitCoordinator(
      TransactionManager transactionManager,
      IndexedTable indexedTable,
      long maximumGroupDelayNanos) {
    manager = transactionManager;
    table = indexedTable;
    groupDelayNanos = maximumGroupDelayNanos;
  }

  public StatusCode commit(IndexedTransactionSession session, TransactionOutcome result) {
    if (session == null || result == null || !session.eligibleForCommitGroup()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int slot = -1;
    long batch = 0;
    boolean leader = false;
    while (slot < 0) {
      synchronized (this) {
        if (!batchActive && remainingReaders == 0) {
          batchActive = true;
          accepting = true;
          activeBatch = nextBatch++;
          requestCount = 0;
          leaderThread = Thread.currentThread();
          leader = true;
        }
        if (batchActive
            && accepting
            && requestCount < sessions.length) {
          slot = requestCount++;
          batch = activeBatch;
          sessions[slot] = session;
          transactions[slot] = session.groupTransaction();
          outcomes[slot] = result;
          statuses[slot] = null;
          commitSequences[slot] = 0;
          if (requestCount == sessions.length && leaderThread != null) {
            accepting = false;
            LockSupport.unpark(leaderThread);
          }
        }
      }
      if (slot < 0) {
        LockSupport.parkNanos(50_000);
      }
    }
    if (leader) {
      collectAndProcess(batch);
    } else {
      while (completedBatch < batch) {
        LockSupport.parkNanos(50_000);
      }
    }
    StatusCode status = statuses[slot];
    synchronized (this) {
      remainingReaders--;
      if (remainingReaders == 0) {
        clearCompletedBatch();
      }
    }
    return status;
  }

  private void collectAndProcess(long batch) {
    long started = System.nanoTime();
    long elapsed = 0;
    while (accepting && elapsed < groupDelayNanos) {
      LockSupport.parkNanos(groupDelayNanos - elapsed);
      elapsed = System.nanoTime() - started;
    }
    int count;
    synchronized (this) {
      accepting = false;
      count = requestCount;
    }
    process(count);
    synchronized (this) {
      remainingReaders = count;
      completedBatch = batch;
      batchActive = false;
      leaderThread = null;
    }
  }

  private void process(int count) {
    StatusCode status = table.preflightPreparedCommitGroup(sessions, count);
    if (!status.isOk()) {
      commitDirectly(count);
      return;
    }
    status = manager.beginCommitGroup(transactions, count);
    if (!status.isOk()) {
      table.cancelPreparedInsertGroup();
      completeAll(count, status);
      return;
    }
    for (int index = 0; status.isOk() && index < count; index++) {
      long commitSequence = table.nextCommitSequence();
      commitSequences[index] = commitSequence;
      status = table.appendPreparedWrites(sessions[index], commitSequence);
    }
    if (status.isOk()) {
      status = table.forcePreparedInserts();
    }
    if (!status.isOk()) {
      manager.failCommitGroup(transactions, outcomes, count, status);
      completeAll(count, status);
      return;
    }
    status = manager.publishCommitGroup(
        transactions,
        outcomes,
        commitSequences,
        count,
        table);
    completeAll(count, status);
  }

  private void commitDirectly(int count) {
    for (int index = 0; index < count; index++) {
      StatusCode status = sessions[index].commitDirect(outcomes[index]);
      statuses[index] = sessions[index].completeCoordinatedCommit(status);
    }
  }

  private void completeAll(int count, StatusCode status) {
    for (int index = 0; index < count; index++) {
      statuses[index] = sessions[index].completeCoordinatedCommit(status);
    }
  }

  private void clearCompletedBatch() {
    for (int index = 0; index < requestCount; index++) {
      sessions[index] = null;
      transactions[index] = null;
      outcomes[index] = null;
      statuses[index] = null;
      commitSequences[index] = 0;
    }
    requestCount = 0;
    activeBatch = 0;
  }
}
