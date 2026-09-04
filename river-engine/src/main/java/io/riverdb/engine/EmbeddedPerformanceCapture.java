package io.riverdb.engine;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.table.IndexedGroupCommitTelemetry;
import io.riverdb.engine.table.IndexedCommitPath;
import io.riverdb.engine.table.IndexedCommitStage;
import io.riverdb.engine.table.IndexedTable;
import io.riverdb.tx.TransactionManager;
import io.riverdb.wal.local.LocalWalForceCause;
import io.riverdb.wal.local.LocalWalMetrics;

/** One explicit aggregate-only metrics window bracketed by quiescent boundaries. */
final class EmbeddedPerformanceCapture {
  private final IndexedTable table;
  private final TransactionManager transactions;
  private final IndexedGroupCommitTelemetry commits = new IndexedGroupCommitTelemetry();
  private final LocalWalMetrics forces = new LocalWalMetrics();
  private long waitsEntered;
  private long waitsBlocked;
  private long blockedNanos;
  private long waitsGranted;
  private long waitsTimedOut;
  private long waitsDeadlocked;
  private long waitsCancelled;
  private long capturedWaitsEntered;
  private long capturedWaitsBlocked;
  private long capturedBlockedNanos;
  private long capturedWaitsGranted;
  private long capturedWaitsTimedOut;
  private long capturedWaitsDeadlocked;
  private long capturedWaitsCancelled;
  private boolean active;

  EmbeddedPerformanceCapture(IndexedTable indexedTable, TransactionManager transactionManager) {
    table = indexedTable;
    transactions = transactionManager;
  }

  synchronized StatusCode begin() {
    if (active) return StatusCode.CONFLICT;
    return transactions.atQuiescentBoundary(this::beginAtBoundary);
  }

  private StatusCode beginAtBoundary() {
    waitsEntered = transactions.lockWaitsEntered();
    waitsBlocked = transactions.lockWaitsActuallyBlocked();
    blockedNanos = transactions.lockWaitBlockedNanos();
    waitsGranted = transactions.lockWaitsGranted();
    waitsTimedOut = transactions.lockWaitsTimedOut();
    waitsDeadlocked = transactions.lockWaitsDeadlocked();
    waitsCancelled = transactions.lockWaitsCancelled();
    StatusCode status = table.beginPerformanceCapture();
    if (status.isOk()) active = true;
    return status;
  }

  synchronized StatusCode end(StringBuilder target) {
    if (!active || target == null) return StatusCode.CONFLICT;
    StatusCode status = transactions.atQuiescentBoundary(this::endAtBoundary);
    if (!status.isOk()) return status;
    boolean valid = commits.reconciles()
        && forces.reconciles()
        && commitForcesReconcile();
    target.append("server_performance_capture_scope=quiescent_window\n")
        .append("server_performance_capture_valid=").append(valid).append('\n')
        .append("server_capture_lock_waits_entered=").append(capturedWaitsEntered).append('\n')
        .append("server_capture_lock_waits_actually_blocked=")
        .append(capturedWaitsBlocked).append('\n')
        .append("server_capture_lock_wait_blocked_nanos=")
        .append(capturedBlockedNanos).append('\n')
        .append("server_capture_lock_waits_granted=").append(capturedWaitsGranted).append('\n')
        .append("server_capture_lock_waits_timed_out=")
        .append(capturedWaitsTimedOut).append('\n')
        .append("server_capture_lock_waits_deadlocked=")
        .append(capturedWaitsDeadlocked).append('\n')
        .append("server_capture_lock_waits_cancelled=")
        .append(capturedWaitsCancelled).append('\n');
    EmbeddedCommitDiagnostics.append(target, commits, forces, "capture_");
    return StatusCode.OK;
  }

  private StatusCode endAtBoundary() {
    StatusCode status = table.endPerformanceCapture(commits, forces);
    active = false;
    if (!status.isOk()) return status;
    capturedWaitsEntered = delta(waitsEntered, transactions.lockWaitsEntered());
    capturedWaitsBlocked = delta(waitsBlocked, transactions.lockWaitsActuallyBlocked());
    capturedBlockedNanos = delta(blockedNanos, transactions.lockWaitBlockedNanos());
    capturedWaitsGranted = delta(waitsGranted, transactions.lockWaitsGranted());
    capturedWaitsTimedOut = delta(waitsTimedOut, transactions.lockWaitsTimedOut());
    capturedWaitsDeadlocked = delta(waitsDeadlocked, transactions.lockWaitsDeadlocked());
    capturedWaitsCancelled = delta(waitsCancelled, transactions.lockWaitsCancelled());
    if (capturedWaitsEntered < 0 || capturedWaitsBlocked < 0 || capturedBlockedNanos < 0
        || capturedWaitsGranted < 0 || capturedWaitsTimedOut < 0
        || capturedWaitsDeadlocked < 0 || capturedWaitsCancelled < 0) {
      return StatusCode.INVARIANT_BROKEN;
    }
    return StatusCode.OK;
  }

  synchronized StatusCode cancelIfActive() {
    if (!active) return StatusCode.OK;
    StatusCode status = table.cancelPerformanceCapture();
    active = false;
    return status;
  }

  private boolean commitForcesReconcile() {
    return commits.stageCount(
        IndexedCommitPath.SHARED_GROUP, IndexedCommitStage.GROUP_FORCE)
            == forces.forceCount(LocalWalForceCause.SHARED_GROUP)
        && commits.stageCount(
            IndexedCommitPath.DIRECT_COMMIT, IndexedCommitStage.DIRECT_FORCE)
            == forces.forceCount(LocalWalForceCause.DIRECT_COMMIT);
  }

  private static long delta(long start, long end) {
    return end < start ? -1 : end - start;
  }
}
