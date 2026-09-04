package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.tx.TransactionManager;
import io.riverdb.tx.api.TransactionOutcome;
import java.util.concurrent.locks.LockSupport;

/** Database-owned reactive queue feeding the single local-WAL commit writer. */
public final class IndexedGroupCommitCoordinator {
  private final long initialCoalescingNanos;
  private final TransactionManager manager;
  private final IndexedTable table;
  private final IndexedGroupCommitMetrics metrics;
  private final IndexedGroupCommitBatch batch;
  private final StatusCode capacityStatus;
  private final Thread writer;
  private IndexedGroupCommitRequest queueHead;
  private IndexedGroupCommitRequest queueTail;
  private Thread closingThread;
  private int queued;
  private int queuedGroupable;
  private long queueBecameNonemptyNanos;
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
    this(transactionManager, indexedTable, firstCohortMaximumWaitNanos, null);
  }

  IndexedGroupCommitCoordinator(
      TransactionManager transactionManager,
      IndexedTable indexedTable,
      long firstCohortMaximumWaitNanos,
      IndexedGroupCommitBatch writerBatch) {
    initialCoalescingNanos = firstCohortMaximumWaitNanos;
    manager = transactionManager;
    table = indexedTable;
    metrics = indexedTable.commitMetrics();
    batch = writerBatch == null
        ? new IndexedGroupCommitBatch(transactionManager, indexedTable, metrics)
        : writerBatch;
    capacityStatus = indexedTable.reserveHybridCommitGroupCapacity(batch.capacity());
    writer = Thread.ofVirtual()
        .name("river-wal-commit-" + Integer.toHexString(System.identityHashCode(this)))
        .start(this::run);
  }

  boolean matches(TransactionManager transactionManager, IndexedTable indexedTable) {
    return manager == transactionManager && table == indexedTable;
  }

  StatusCode commit(IndexedGroupCommitRequest request, TransactionOutcome result) {
    if (!capacityStatus.isOk()) {
      metrics.recordFailedBefore(capacityStatus);
      return capacityStatus;
    }
    if (!validCommit(request, result)) {
      metrics.recordFailedBefore(StatusCode.INVALID_EXTERNAL_INPUT);
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int eligibilityMask = request.session.commitGroupEligibilityMask();
    IndexedCommitPath logicalPath = eligibilityMask == 0
        ? IndexedCommitPath.SHARED_GROUP : IndexedCommitPath.DIRECT_COMMIT;
    long logicalStarted = System.nanoTime();
    StatusCode logical = request.session.prepareLogicalCommit();
    metrics.recordStage(
        logicalPath,
        IndexedCommitStage.LOGICAL_PREPARATION,
        System.nanoTime() - logicalStarted);
    if (!logical.isOk()) {
      metrics.recordStageFailure(
          logicalPath, IndexedCommitStage.LOGICAL_PREPARATION, logical);
    }
    if (!logical.isOk()) {
      metrics.recordFailedBefore(logical);
      return logical;
    }
    long ticket = request.prepare(
        result,
        eligibilityMask,
        metrics);
    if (ticket == 0) {
      request.session.cancelLogicalCommit();
      metrics.recordFailedBefore(StatusCode.CONFLICT);
      return StatusCode.CONFLICT;
    }
    StatusCode admission;
    boolean accepted;
    boolean wake;
    synchronized (this) {
      accepted = !closing;
      if (accepted) {
        admission = request.session.prepareCoordinatedCommit(request.outcome);
        accepted = admission.isOk();
        if (accepted) {
          metrics.recordWriteSubmission(eligibilityMask, true);
          enqueue(request);
        } else {
          metrics.recordFailedBefore(admission);
        }
      } else {
        admission = StatusCode.CLOSED;
        metrics.recordFailedBefore(StatusCode.CLOSED);
      }
      wake = accepted && writerIdle;
      if (wake) writerIdle = false;
    }
    if (wake) LockSupport.unpark(writer);
    if (!accepted) {
      if (request.session.groupTransaction().state()
          == io.riverdb.tx.api.TransactionState.ACTIVE) {
        request.session.cancelLogicalCommit();
      }
      StatusCode completion = request.completeOnce(
          request.session.completeCoordinatedCommit(admission));
      if (!completion.isOk()) table.fenceCommitWriter();
    }
    return request.await(ticket, result);
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

  /** Copies the shared table commit funnel into caller-owned storage. */
  public StatusCode copyTelemetry(IndexedGroupCommitTelemetry result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    metrics.copyTo(result);
    return StatusCode.OK;
  }

  void recordReadOnlyCommit() { metrics.recordReadOnlyCommit(); }
  void recordFailedBefore(StatusCode status) { metrics.recordFailedBefore(status); }

  private void run() {
    int activeCount = 0;
    try {
      while (true) {
        long waitNanos = coalescingWaitNanos();
        if (waitNanos > 0) {
          long started = System.nanoTime();
          LockSupport.parkNanos(waitNanos);
          metrics.recordCoalescingWait(System.nanoTime() - started);
        }
        synchronized (this) {
          activeCount = drain();
          if (activeCount == 0 && closing) {
            stopWriter();
            return;
          }
        }
        if (activeCount == 0) {
          awaitWork();
        } else {
          long started = System.nanoTime();
          batch.process(activeCount);
          batch.complete(activeCount);
          activeCount = 0;
          metrics.recordWriterBusy(System.nanoTime() - started);
        }
      }
    } catch (Throwable unexpected) {
      failWriter(activeCount);
    }
  }

  private void failWriter(int activeCount) {
    table.fenceCommitWriter();
    synchronized (this) {
      closing = true;
    }
    if (activeCount > 0) {
      batch.failUnexpected(activeCount);
      batch.complete(activeCount);
    }
    while (true) {
      int count;
      synchronized (this) {
        count = drain();
        if (count == 0) {
          stopWriter();
          return;
        }
      }
      batch.failUnexpected(count);
      batch.complete(count);
    }
  }

  private void stopWriter() {
    stopped = true;
    LockSupport.unpark(closingThread);
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

  private long coalescingWaitNanos() {
    synchronized (this) {
      if (closing || queued == 0) return 0;
      return initialCoalescingNanos > 0 ? initialCoalescingNanos : 0;
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
    if (request.groupable) queuedGroupable++;
    if (queued == 1) queueBecameNonemptyNanos = System.nanoTime();
    metrics.recordQueueEnqueue(queued);
  }

  private int drain() {
    int count = 0;
    int depth = queued;
    int groupableDepth = queuedGroupable;
    boolean groupable = queueHead != null && queueHead.groupable;
    int maximum = groupable ? batch.capacity() : 1;
    while (queueHead != null && count < maximum && queueHead.groupable == groupable) {
      IndexedGroupCommitRequest request = queueHead;
      queueHead = request.next;
      request.next = null;
      batch.add(count++, request);
      queued--;
      if (request.groupable) queuedGroupable--;
    }
    if (queueHead == null) queueTail = null;
    boolean capacityConstrained = groupable && queueHead != null && queueHead.groupable;
    if (count > 0) {
      metrics.recordWriterSelection(
          count, depth, groupableDepth, groupable, capacityConstrained);
    }
    if (queued == 0 && queueBecameNonemptyNanos != 0) {
      metrics.recordQueueNonempty(System.nanoTime() - queueBecameNonemptyNanos);
      queueBecameNonemptyNanos = 0;
    }
    return count;
  }
}
