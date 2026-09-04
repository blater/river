package io.riverdb.tx;

import io.riverdb.base.error.StatusCode;
import io.riverdb.tx.api.lock.LockExecutionLane;
import io.riverdb.tx.api.lock.LockMode;
import io.riverdb.tx.api.lock.LockRequest;
import io.riverdb.tx.api.lock.LockScope;
import io.riverdb.tx.api.lock.LockToken;
import io.riverdb.tx.api.lock.LockWaitHandle;
import io.riverdb.tx.api.lock.LockWaitState;

/** Reactive canonical lock table for exact resources. */
final class LockExactTable {
  static final LockWaitState[] WAIT_STATES = LockWaitState.values();
  static final LockMode[] LOCK_MODES = LockMode.values();
  static final LockScope[] LOCK_SCOPES = LockScope.values();
  static final long PROVIDER_GENERATION = 2;
  final Object authority;
  final LockExactState state;
  final LockExactUnlink unlink;
  final LockExactAdmission admission = new LockExactAdmission();
  final LockExactConflicts conflicts;
  final LockExactScheduler scheduler;
  final LockExactAdmissionController admissions;
  final LockExactTransactionAdmission transactionAdmission;
  final LockExactHoldingLifecycle holdingLifecycle;
  final LockExactRequestLifecycle requestLifecycle;
  final LockExactLifecycle lifecycle;
  final LockExactDeadlockDetector deadlocks;
  final LockWaitCounters waitCounters = new LockWaitCounters();
  long nextCapability = 1;
  long nextReference = 1;
  long nextRequest = 1;
  long holdingCount;
  long waitingCount;

  LockExactTable(Object providerAuthority, long seed, LockSegmentArena arena) {
    this(providerAuthority, seed, arena, LockDeadlockDiagnosticsConfig.disabled());
  }

  LockExactTable(
      Object providerAuthority, long seed, LockSegmentArena arena,
      LockDeadlockDiagnosticsConfig diagnosticsConfig) {
    authority = providerAuthority;
    state = new LockExactState(arena, seed);
    unlink = new LockExactUnlink(state);
    conflicts = new LockExactConflicts(this);
    scheduler = new LockExactScheduler(this);
    admissions = new LockExactAdmissionController(this);
    transactionAdmission = new LockExactTransactionAdmission(this);
    holdingLifecycle = new LockExactHoldingLifecycle(this);
    requestLifecycle = new LockExactRequestLifecycle(this);
    lifecycle = new LockExactLifecycle(this);
    deadlocks = new LockExactDeadlockDetector(this, diagnosticsConfig);
  }

  StatusCode tryAcquire(
      long transactionId, long transactionGeneration, long transactionStartOrder,
      LockRequest request, LockToken token) {
    if (!valid(transactionId, transactionGeneration, request) || token == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode graphStatus = deadlocks.canAdmit(
        transactionId, transactionGeneration, transactionStartOrder, false);
    if (!graphStatus.isOk()) return graphStatus;
    if (token.isActive()) return StatusCode.CONFLICT;
    StatusCode blocked = lifecycle.blockedStatus(transactionId, transactionGeneration);
    if (!blocked.isOk()) return blocked;
    long resource = state.directory.resource(request);
    long holding = resource < 0 ? -1
        : state.directory.holding(resource, transactionId, transactionGeneration);
    if (holding >= 0) {
      LockExactHoldingStore.Chunk chunk = state.holdings.record(holding);
      if (chunk.active[LockTypedSlots.offset(holding)] == 0) return StatusCode.RETRY;
      return holdingLifecycle.acquire(resource, holding, request.mode(), token);
    }
    if (LockIntervalIndex.valid(request)) {
      if (conflicts.activeBlocked(request, transactionId, transactionGeneration)
          || conflicts.conversionBlocked(request, transactionId, transactionGeneration)
          || conflicts.earlierBlocked(request, transactionId, transactionGeneration)) {
        return StatusCode.RETRY;
      }
    } else if (resource >= 0 && scheduler.blocked(resource, request.mode())) {
      return StatusCode.RETRY;
    }
    return createHolding(transactionId, transactionGeneration, transactionStartOrder,
        resource, request, token);
  }

  StatusCode enqueue(
      long transactionId, long transactionGeneration, long transactionStartOrder,
      long laneId, long laneGeneration, LockRequest request,
      LockExecutionLane lane, LockWaitHandle handle) {
    StatusCode blocked = lifecycle.blockedStatus(transactionId, transactionGeneration);
    if (!blocked.isOk()) return blocked;
    return admissions.enqueue(transactionId, transactionGeneration, transactionStartOrder,
        laneId, laneGeneration, request, lane, handle);
  }

  StatusCode enqueue(
      long transactionId, long transactionGeneration, long transactionStartOrder,
      long laneId, long laneGeneration, LockRequest request,
      LockExecutionLane lane, LockWaitHandle handle, long blockedClockNanos) {
    StatusCode blocked = lifecycle.blockedStatus(transactionId, transactionGeneration);
    if (!blocked.isOk()) return blocked;
    return admissions.enqueue(transactionId, transactionGeneration, transactionStartOrder,
        laneId, laneGeneration, request, lane, handle, blockedClockNanos);
  }

  StatusCode consume(
      LockExecutionLane lane, LockWaitHandle handle, LockToken token) {
    StatusCode blocked = lifecycle.blockedStatus(lane, handle);
    if (!blocked.isOk()) return blocked;
    return requestLifecycle.consume(lane, handle, token);
  }

  StatusCode cancel(LockExecutionLane lane, LockWaitHandle handle, StatusCode outcome) {
    if (lifecycle.frozen(lane, handle)) return StatusCode.RETRY;
    return requestLifecycle.cancel(lane, handle, outcome);
  }

  StatusCode release(LockToken token) {
    return holdingLifecycle.release(token);
  }

  StatusCode retain(LockToken token) {
    return holdingLifecycle.retain(token);
  }

  StatusCode holds(
      long transactionId, long transactionGeneration, LockRequest request) {
    if (!valid(transactionId, transactionGeneration, request)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    long resource = state.directory.resource(request);
    long holding = resource < 0 ? -1
        : state.directory.holding(resource, transactionId, transactionGeneration);
    return holdingLifecycle.holds(holding, request.mode());
  }

  StatusCode acknowledge(LockToken token) {
    return holdingLifecycle.acknowledge(token);
  }

  StatusCode upgrade(LockToken token, LockMode mode) {
    return holdingLifecycle.upgrade(token, mode);
  }

  long remainingNanos(
      LockExecutionLane lane, LockWaitHandle handle, long nowNanos) {
    return requestLifecycle.remainingNanos(lane, handle, nowNanos);
  }

  StatusCode acknowledge(LockExecutionLane lane, LockWaitHandle handle) {
    return requestLifecycle.acknowledge(lane, handle);
  }

  StatusCode validateGranted(LockExecutionLane lane, LockWaitHandle handle) {
    return requestLifecycle.validateGranted(lane, handle);
  }

  StatusCode arm(
      LockExecutionLane lane, LockWaitHandle handle, Thread waitingThread) {
    return requestLifecycle.arm(lane, handle, waitingThread);
  }

  long holdingCount() { return holdingCount; }
  long waitingCount() { return waitingCount; }
  long targetedWakes() { return scheduler.targetedWakes(); }
  long overlapSearches() { return scheduler.overlapSearches(); }
  long deadlockVictimSelections() { return deadlocks.victimSelections(); }
  long lockWaitsEntered() { return waitCounters.enteredCount(); }
  long lockWaitsActuallyBlocked() { return waitCounters.actuallyBlockedCount(); }
  long lockWaitBlockedNanos() { return waitCounters.blockedNanos(); }
  long lockWaitsGranted() { return waitCounters.grantedCount(); }
  long lockWaitsTimedOut() { return waitCounters.timedOutCount(); }
  long lockWaitsDeadlocked() { return waitCounters.deadlockCount(); }
  long lockWaitsCancelled() { return waitCounters.cancelledCount(); }
  StatusCode activateTransaction(
      long id, long generation, long startOrder,
      long diagnosticTag, long diagnosticStepTag, long metricsEpoch) {
    return transactionAdmission.activate(
        id, generation, startOrder, diagnosticTag, diagnosticStepTag, metricsEpoch);
  }

  StatusCode updateDiagnosticStep(long id, long generation, long diagnosticStepTag) {
    return transactionAdmission.updateDiagnosticStep(id, generation, diagnosticStepTag);
  }
  void snapshotDeadlocks(LockDeadlockDiagnosticsSnapshot target) {
    deadlocks.snapshot(target);
  }
  boolean deadlocked(long transactionId, long transactionGeneration) {
    return lifecycle.deadlocked(transactionId, transactionGeneration);
  }

  private StatusCode createHolding(
      long id, long generation, long startOrder,
      long resource, LockRequest request, LockToken token) {
    return admissions.createHolding(id, generation, startOrder, resource, request, token);
  }

  static boolean valid(long id, long generation, LockRequest request) {
    return id > 0 && generation > 0 && request != null
        && request.scope() != null && request.mode() != null
        && (LockIntervalIndex.intervalScope((byte) request.scope().ordinal())
            ? LockIntervalIndex.valid(request)
            : LockResourceOverlap.isValid(request.scope(), request.lowerSpace(), request.lowerKey(),
                request.upperSpace(), request.upperKey()));
  }

}
