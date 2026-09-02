package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.tx.TransactionManager;
import io.riverdb.tx.api.TransactionOutcome;
import java.util.concurrent.locks.LockSupport;

/** Database-owned reactive queue feeding the single local-WAL commit writer. */
public final class IndexedGroupCommitCoordinator {
  private static final long MAXIMUM_ADAPTIVE_COALESCING_NANOS = 1_000_000;
  private final TransactionManager manager;
  private final long initialCoalescingNanos;
  private final IndexedGroupCommitMetrics metrics = new IndexedGroupCommitMetrics();
  private final IndexedGroupCommitBatch batch;
  private final Thread writer;
  private IndexedGroupCommitRequest queueHead;
  private IndexedGroupCommitRequest queueTail;
  private Thread closingThread;
  private int queued;
  private boolean closing;
  private boolean writerIdle;
  private volatile boolean stopped;

  public IndexedGroupCommitCoordinator(
      TransactionManager transactionManager,
      IndexedTable indexedTable) {
    this(transactionManager, indexedTable, 0);
  }

  IndexedGroupCommitCoordinator(
      TransactionManager transactionManager,
      IndexedTable indexedTable,
      long firstCohortMaximumWaitNanos) {
    manager = transactionManager;
    initialCoalescingNanos = firstCohortMaximumWaitNanos;
    batch = new IndexedGroupCommitBatch(transactionManager, indexedTable, metrics);
    writer = Thread.ofVirtual()
        .name("river-wal-commit-" + Integer.toHexString(System.identityHashCode(this)))
        .start(this::run);
  }

  StatusCode commit(IndexedGroupCommitRequest request, TransactionOutcome result) {
    if (!validCommit(request, result)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    long ticket = request.prepare(result, request.session.eligibleForCommitGroup());
    if (ticket == 0) return StatusCode.CONFLICT;
    boolean accepted;
    boolean wake;
    synchronized (this) {
      accepted = !closing;
      if (accepted) enqueue(request);
      wake = accepted && writerIdle;
      if (wake) writerIdle = false;
    }
    if (wake) LockSupport.unpark(writer);
    if (!accepted) {
      request.complete(StatusCode.CLOSED);
    }
    StatusCode status = request.await(ticket, result);
    return request.session.completeCoordinatedCommit(status);
  }

  public StatusCode close() {
    synchronized (this) {
      if (stopped || closing) return StatusCode.CLOSED;
      closing = true;
      closingThread = Thread.currentThread();
    }
    LockSupport.unpark(writer);
    IndexedGroupCommitStopWait.await(this);
    return StatusCode.OK;
  }

  boolean stopped() { return stopped; }

  private static boolean validCommit(
      IndexedGroupCommitRequest request, TransactionOutcome result) {
    return request != null && result != null && request.session.hasCommitWork();
  }

  public long cohortCount() { return metrics.cohorts; }
  public long submittedTransactions() { return metrics.submitted; }
  public long sharedForceTransactions() { return metrics.shared; }
  public long directFallbackTransactions() { return metrics.directFallbacks; }
  public long forceCount() { return metrics.forces; }
  public long coalescingWaitCount() { return metrics.waits; }
  public long lastForceNanos() { return metrics.lastForceNanos; }
  public int maximumCohortSize() { return metrics.maximumCohort; }

  private void run() {
    while (true) {
      long waitNanos = coalescingWaitNanos();
      if (waitNanos > 0) {
        metrics.waits++;
        LockSupport.parkNanos(waitNanos);
      }
      int count;
      synchronized (this) {
        count = drain();
        if (count == 0 && closing) {
          stopped = true;
          LockSupport.unpark(closingThread);
          return;
        }
      }
      if (count == 0) {
        awaitWork();
      } else {
        batch.process(count);
        batch.complete(count);
      }
    }
  }

  private long coalescingWaitNanos() {
    int active = manager.activeTransactionCount();
    synchronized (this) {
      if (closing || queued == 0) return 0;
      if (initialCoalescingNanos > 0) return initialCoalescingNanos;
      return active > queued
          ? Math.min(metrics.estimatedForceNanos, MAXIMUM_ADAPTIVE_COALESCING_NANOS) : 0;
    }
  }

  private void awaitWork() {
    synchronized (this) {
      writerIdle = queued == 0 && !closing;
    }
    if (writerIdle) LockSupport.park();
    synchronized (this) {
      writerIdle = false;
    }
  }

  private void enqueue(IndexedGroupCommitRequest request) {
    request.next = null;
    if (queueTail == null) {
      queueHead = request;
    } else {
      queueTail.next = request;
    }
    queueTail = request;
    queued++;
  }

  private int drain() {
    int count = 0;
    boolean groupable = queueHead != null && queueHead.groupable;
    int maximum = groupable ? batch.capacity() : 1;
    while (queueHead != null && count < maximum && queueHead.groupable == groupable) {
      IndexedGroupCommitRequest request = queueHead;
      queueHead = request.next;
      request.next = null;
      batch.add(count++, request);
      queued--;
    }
    if (queueHead == null) queueTail = null;
    return count;
  }
}
