package io.riverdb.tx;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.tx.api.TransactionContext;
import io.riverdb.tx.api.lock.LockExecutionLane;
import io.riverdb.tx.api.lock.LockMode;
import io.riverdb.tx.api.lock.LockRequest;
import io.riverdb.tx.api.lock.LockService;
import io.riverdb.tx.api.lock.LockToken;
import io.riverdb.tx.api.lock.LockWaitHandle;
import java.util.concurrent.atomic.AtomicLong;

/** Byte-bounded canonical exact and interval lock service. */
public final class LockManager implements LockService {
  private static final long FIXED_PROVIDER_BYTES = 4_096;
  private static final AtomicLong PROVIDER_IDENTITIES = new AtomicLong(1);

  final long ownerLow = PROVIDER_IDENTITIES.getAndIncrement();
  final Object authority = new Object();
  private final LockSegmentArena arena;
  final LockExactTable exact;
  final LockTransactionLifecycle lifecycle;
  private final LockServiceOperations operations;
  private final LockServiceWaits waits;
  private final LockDeadlockDiagnosticsConfig diagnosticsConfig;

  public LockManager(LockMemoryEnvelope envelope) {
    this(envelope, LockDeadlockDiagnosticsConfig.disabled());
  }

  public LockManager(
      LockMemoryEnvelope envelope, LockDeadlockDiagnosticsConfig diagnosticConfig) {
    if (diagnosticConfig == null) {
      throw new IllegalArgumentException("deadlock diagnostic config is required");
    }
    arena = new LockSegmentArena(envelope);
    if (!arena.reserve(FIXED_PROVIDER_BYTES).isOk()) {
      throw new IllegalArgumentException("lock memory envelope too small");
    }
    diagnosticsConfig = diagnosticConfig;
    exact = new LockExactTable(authority, ownerLow, arena, diagnosticConfig);
    lifecycle = new LockTransactionLifecycle(this);
    operations = new LockServiceOperations(this);
    waits = new LockServiceWaits(this);
  }

  public synchronized long activeLockCount() { return exact.holdingCount(); }
  public synchronized long waitingCount() { return exact.waitingCount(); }
  public synchronized long deadlockVictimSelections() {
    return exact.deadlockVictimSelections();
  }
  public synchronized long lockWaitsEntered() { return exact.lockWaitsEntered(); }
  public synchronized long lockWaitsActuallyBlocked() {
    return exact.lockWaitsActuallyBlocked();
  }
  public synchronized long lockWaitBlockedNanos() { return exact.lockWaitBlockedNanos(); }
  public synchronized long lockWaitsGranted() { return exact.lockWaitsGranted(); }
  public synchronized long lockWaitsTimedOut() { return exact.lockWaitsTimedOut(); }
  public synchronized long lockWaitsDeadlocked() { return exact.lockWaitsDeadlocked(); }
  public synchronized long lockWaitsCancelled() { return exact.lockWaitsCancelled(); }
  public boolean lockEscalationSupported() { return LockWaitCounters.escalationSupported(); }
  public long lockEscalationCount() { return LockWaitCounters.escalationCount(); }
  synchronized long accountedBytes() { return arena.accountedBytes(); }
  synchronized long targetedWakes() { return exact.targetedWakes(); }
  public LockDeadlockDiagnosticsSnapshot newDeadlockDiagnosticsSnapshot() {
    return new LockDeadlockDiagnosticsSnapshot(diagnosticsConfig);
  }

  public synchronized StatusCode snapshotDeadlockDiagnostics(
      LockDeadlockDiagnosticsSnapshot target) {
    if (target == null || !target.compatible(diagnosticsConfig)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    exact.snapshotDeadlocks(target);
    return StatusCode.OK;
  }

  @Override
  public StatusCode tryAcquire(
      TransactionContext context, long generation, LockRequest request,
      long nowNanos, LockToken token, StatusDetail detail) {
    return operations.tryAcquire(context, generation, request, nowNanos, token, detail);
  }

  @Override
  public StatusCode release(
      TransactionContext context, long generation,
      LockToken token, StatusDetail detail) {
    return operations.release(context, generation, token, detail);
  }

  @Override
  public StatusCode retain(
      TransactionContext context, long generation,
      LockToken token, StatusDetail detail) {
    return operations.retain(context, generation, token, detail);
  }

  @Override
  public StatusCode holds(
      TransactionContext context, long generation,
      LockRequest request, StatusDetail detail) {
    return operations.holds(context, generation, request, detail);
  }

  synchronized StatusCode release(LockToken token) { return exact.release(token); }

  @Override
  public StatusCode acknowledge(LockToken token, StatusDetail detail) {
    return operations.acknowledge(token, detail);
  }

  synchronized StatusCode upgrade(LockToken token, LockMode mode) {
    return exact.upgrade(token, mode);
  }

  @Override
  public StatusCode enqueue(
      TransactionContext context, long generation,
      long laneId, long laneGeneration,
      LockRequest request, long nowNanos,
      LockExecutionLane lane, LockWaitHandle handle, StatusDetail detail) {
    return waits.enqueue(context, generation, laneId, laneGeneration,
        request, nowNanos, lane, handle, detail);
  }

  @Override
  public StatusCode await(
      LockExecutionLane lane, LockWaitHandle handle, StatusDetail detail) {
    return waits.await(lane, handle, detail);
  }

  @Override
  public StatusCode consume(
      TransactionContext context, long generation,
      LockExecutionLane lane, LockWaitHandle handle,
      LockToken token, StatusDetail detail) {
    return operations.consume(context, generation, lane, handle, token, detail);
  }

  @Override
  public StatusCode cancel(
      LockExecutionLane lane, LockWaitHandle handle, StatusDetail detail) {
    return waits.cancel(lane, handle, detail);
  }
}
