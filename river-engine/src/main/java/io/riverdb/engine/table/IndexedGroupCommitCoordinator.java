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
  private final StatusCode capacityStatus;
  private final Thread writer;
  private IndexedGroupCommitRequest queueHead;
  private IndexedGroupCommitRequest queueTail;
  private Thread closingThread;
  private int queued;
  private int queuedGroupable;
  private int selectedDepth;
  private int selectedGroupableDepth;
  private long queueBecameNonemptyNanos;
  private boolean selectedGroupable;
  private boolean selectedCapacityConstrained;
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
          StatusCode forceStatus = completeForce();
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
          activeCount = drain(selectionCapacity());
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
          if (handled > 0) recordWriterSelection(activeCount, handled);
          if (wasEmpty && !pending.empty()) {
            table.pendingDurabilitySequence(pending.firstCommitSequence());
          }
          if (handled < activeCount) {
            synchronized (this) {
              requeueDeferred(handled, activeCount);
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
      recordWriterSelection(activeCount, activeCount);
    }
    failPending(StatusCode.INVARIANT_BROKEN);
    while (true) {
      int count;
      synchronized (this) {
        count = drain(batch.capacity());
        if (count == 0) {
          stopWriter();
          return;
        }
      }
      batch.failUnexpected(count);
      batch.complete(count);
      recordWriterSelection(count, count);
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
      boolean selectable = !durabilityOnly && selectableWorkAvailable(pipelineActive);
      park = !forceReady
          && !(closing && queued == 0 && !pipelineActive)
          && (durabilityOnly ? pipelineActive : !selectable);
      if (!park) writerIdle = false;
    }
    if (park) LockSupport.park();
    synchronized (this) {
      writerIdle = false;
    }
  }

  private boolean selectableWorkAvailable(boolean pipelineActive) {
    if (queueHead == null) return false;
    if (!queueHead.groupable) return !pipelineActive;
    return pending.canRetain(1) || pending.empty();
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

  private void requeueDeferred(int first, int end) {
    int initialDepth = queued;
    for (int index = end - 1; index >= first; index--) {
      IndexedGroupCommitRequest request = batch.takeDeferred(index);
      request.next = queueHead;
      queueHead = request;
      if (queueTail == null) queueTail = request;
      queued++;
      if (request.groupable) queuedGroupable++;
    }
    if (initialDepth == 0 && queued > 0) {
      queueBecameNonemptyNanos = System.nanoTime();
    }
  }

  private int drain(int allowed) {
    if (allowed <= 0) return 0;
    int count = 0;
    int depth = queued;
    int groupableDepth = queuedGroupable;
    boolean groupable = queueHead != null && queueHead.groupable;
    int maximum = Math.min(groupable ? batch.capacity() : 1, allowed);
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
      selectedDepth = depth;
      selectedGroupableDepth = groupableDepth;
      selectedGroupable = groupable;
      selectedCapacityConstrained = capacityConstrained;
    }
    if (queued == 0 && queueBecameNonemptyNanos != 0) {
      metrics.recordQueueNonempty(System.nanoTime() - queueBecameNonemptyNanos);
      queueBecameNonemptyNanos = 0;
    }
    return count;
  }

  private int selectionCapacity() {
    if (queueHead == null) return 0;
    if (!queueHead.groupable) {
      return pending.empty() && !table.forceActive() ? 1 : 0;
    }
    if (pending.canRetain(1)) return pending.remainingMembers();
    return pending.empty() ? 1 : 0;
  }

  private StatusCode completeForce() {
    long coveredEnd = table.submittedForceEnd();
    long forceNanos = table.submittedForceNanos();
    StatusCode status = table.completeSubmittedForce();
    metrics.recordStage(
        IndexedCommitPath.SHARED_GROUP, IndexedCommitStage.GROUP_FORCE, forceNanos);
    if (!status.isOk()) {
      table.fenceCommitWriter();
      failPending(status);
      return status;
    }
    status = table.releaseSubmittedForce();
    if (!status.isOk()) {
      table.fenceCommitWriter();
      failPending(status);
      return status;
    }
    while (!pending.empty() && pending.requiredWalEnd() <= coveredEnd) {
      int count = pending.headMemberCount();
      status = pending.loadHead(batch);
      if (status.isOk()) {
        status = table.releaseDurabilityChain(pending.token(), pending.frameHead());
      }
      if (status.isOk()) status = batch.completePublished(count);
      if (!status.isOk()) {
        table.fenceCommitWriter();
        batch.failPublished(count, status);
      }
      batch.complete(count);
      StatusCode removed = pending.removeHead();
      if (!status.isOk() || !removed.isOk()) {
        failPending(status.isOk() ? removed : status);
        return status.isOk() ? removed : status;
      }
    }
    table.pendingDurabilitySequence(pending.firstCommitSequence());
    return StatusCode.OK;
  }

  private void failPending(StatusCode failure) {
    while (!pending.empty()) {
      int count = pending.headMemberCount();
      StatusCode status = pending.loadHead(batch);
      StatusCode release = table.releaseDurabilityChain(pending.token(), pending.frameHead());
      if (status.isOk()) status = batch.failPublished(count, failure);
      if (!release.isOk() || !status.isOk()) table.fenceCommitWriter();
      batch.complete(count);
      if (!pending.removeHead().isOk()) break;
    }
    table.pendingDurabilitySequence(0);
  }

  private void recordWriterSelection(int selected, int completed) {
    metrics.recordWriterSelection(
        completed,
        selectedDepth,
        selectedGroupableDepth,
        selectedGroupable,
        selectedCapacityConstrained || completed < selected);
  }
}
