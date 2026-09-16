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
  private final IndexedDurabilityCohortRing pending;
  private final IndexedCommitRequestQueue queue;
  private final IndexedCommitDurability durability;
  private final StatusCode capacityStatus;
  private final Thread writer;
  private Thread closingThread;
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
    pending = new IndexedDurabilityCohortRing(batch.capacity());
    queue = new IndexedCommitRequestQueue(batch, metrics, pending, table);
    durability = new IndexedCommitDurability(batch, metrics, pending, table);
    writer = Thread.ofVirtual()
        .name("river-wal-commit-" + Integer.toHexString(System.identityHashCode(this)))
        .unstarted(this::run);
    StatusCode status = indexedTable.reserveHybridCommitGroupCapacity(batch.capacity());
    if (status.isOk()) status = indexedTable.enableForceWorker(writer);
    capacityStatus = status;
    writer.start();
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
    StatusCode logical = prepareLogical(request, eligibilityMask);
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
      admission = admit(request, eligibilityMask);
      accepted = admission.isOk();
      wake = accepted && writerIdle;
      if (wake) writerIdle = false;
    }
    if (wake) LockSupport.unpark(writer);
    if (!accepted) reject(request, admission);
    return request.await(ticket, result);
  }

  private StatusCode admit(IndexedGroupCommitRequest request, int eligibilityMask) {
    if (closing) {
      metrics.recordFailedBefore(StatusCode.CLOSED);
      return StatusCode.CLOSED;
    }
    StatusCode status = request.session.prepareCoordinatedCommit(request.outcome);
    if (status.isOk()) {
      metrics.recordWriteSubmission(eligibilityMask, true);
      queue.enqueue(request);
    } else {
      metrics.recordFailedBefore(status);
    }
    return status;
  }

  private void reject(IndexedGroupCommitRequest request, StatusCode admission) {
    if (request.session.groupTransaction().state()
        == io.riverdb.tx.api.TransactionState.ACTIVE) {
      request.session.cancelLogicalCommit();
    }
    StatusCode completion = request.completeOnce(
        request.session.completeCoordinatedCommit(admission));
    if (!completion.isOk()) table.fenceCommitWriter();
  }

  private StatusCode prepareLogical(IndexedGroupCommitRequest request, int eligibilityMask) {
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
    return logical;
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

  synchronized boolean writerIdle() { return writerIdle; }

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
        if (table.forceResultReady()) {
          StatusCode forceStatus = durability.completeForce();
          if (!forceStatus.isOk()) {
            failWriter(activeCount);
            return;
          }
        }
        if (!table.forceActive() && !pending.empty()) {
          StatusCode submit = table.submitSealedForce();
          if (!submit.isOk()) {
            failWriter(activeCount);
            return;
          }
        }
        long waitNanos = coalescingWaitNanos();
        if (waitNanos > 0) {
          long started = System.nanoTime();
          LockSupport.parkNanos(waitNanos);
          metrics.recordCoalescingWait(System.nanoTime() - started);
        }
        synchronized (this) {
          activeCount = queue.drain(queue.selectionCapacity());
          if (activeCount == 0 && closing && pending.empty() && !table.forceActive()) {
            stopWriter();
            return;
          }
        }
        if (activeCount == 0) {
          awaitWork(false);
        } else {
          long started = System.nanoTime();
          boolean wasEmpty = pending.empty();
          boolean overlappedForce = table.forceActive();
          batch.process(activeCount, pending);
          int completed = batch.completionCount();
          int handled = batch.handledCount();
          if (overlappedForce && batch.retainedCount() > 0) {
            metrics.recordPhysicalForceOverlap();
          }
          batch.complete(completed);
          if (handled > 0) queue.recordWriterSelection(activeCount, handled);
          if (wasEmpty && !pending.empty()) {
            table.pendingDurabilitySequence(pending.firstCommitSequence());
          }
          if (handled < activeCount) {
            synchronized (this) {
              queue.requeueDeferred(handled, activeCount);
            }
          }
          boolean blocked = batch.durabilityBlocked();
          activeCount = 0;
          metrics.recordWriterBusy(System.nanoTime() - started);
          if (blocked && table.forceActive()) awaitWork(true);
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
      queue.recordWriterSelection(activeCount, activeCount);
    }
    durability.failPending(StatusCode.INVARIANT_BROKEN);
    while (true) {
      int count;
      synchronized (this) {
        count = queue.drain(batch.capacity());
        if (count == 0) {
          stopWriter();
          return;
        }
      }
      batch.failUnexpected(count);
      batch.complete(count);
      queue.recordWriterSelection(count, count);
    }
  }

  private void stopWriter() {
    stopped = true;
    LockSupport.unpark(closingThread);
  }

  private void awaitWork(boolean durabilityOnly) {
    boolean forceReady = table.forceResultReady();
    boolean pipelineActive = table.forceActive() || !pending.empty();
    boolean park;
    synchronized (this) {
      writerIdle = true;
      park = shouldPark(durabilityOnly, forceReady, pipelineActive);
      if (!park) writerIdle = false;
    }
    if (park) LockSupport.park();
    synchronized (this) {
      writerIdle = false;
    }
  }


  private boolean shouldPark(boolean durabilityOnly, boolean forceReady, boolean pipelineActive) {
    boolean selectable = !durabilityOnly && queue.selectableWorkAvailable(pipelineActive);
    return !forceReady
        && !(closing && queue.empty() && !pipelineActive)
        && (durabilityOnly ? pipelineActive : !selectable);
  }

  private long coalescingWaitNanos() {
    synchronized (this) {
      if (closing || queue.empty()) return 0;
      return initialCoalescingNanos > 0 ? initialCoalescingNanos : 0;
    }
  }







}
