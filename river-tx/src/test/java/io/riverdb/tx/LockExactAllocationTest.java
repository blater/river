package io.riverdb.tx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.management.ThreadMXBean;
import io.riverdb.base.concurrent.MutableCancellationToken;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionContext;
import io.riverdb.tx.api.lock.LockExecutionLane;
import io.riverdb.tx.api.lock.LockMode;
import io.riverdb.tx.api.lock.LockRequest;
import io.riverdb.tx.api.lock.LockService;
import io.riverdb.tx.api.lock.LockToken;
import io.riverdb.tx.api.lock.LockWaitHandle;
import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

final class LockExactAllocationTest {
  private static volatile long allocationGuard;

  @Test
  void warmedExactImmediateAndReactivePathsAllocateNoSteadyStateMemory() {
    java.lang.management.ThreadMXBean standard = ManagementFactory.getThreadMXBean();
    Assumptions.assumeTrue(standard instanceof ThreadMXBean);
    ThreadMXBean bean = (ThreadMXBean) standard;
    Assumptions.assumeTrue(bean.isThreadAllocatedMemorySupported());
    bean.setThreadAllocatedMemoryEnabled(true);

    LockDeadlockDiagnosticsConfig diagnostics =
        LockDeadlockDiagnosticsConfig.bounded(1, 4, 12_000, 1, 4);
    LockManager locks = new LockManager(new LockMemoryEnvelope(8L << 20), diagnostics);
    LockService service = locks;
    TransactionContext owner = context(locks, 1);
    TransactionContext waiter = context(locks, 2);
    LockRequest request = new LockRequest().setExact(
        io.riverdb.tx.api.lock.LockScope.ROW, 17, 19, LockMode.EXCLUSIVE, 0);
    LockRequest cycleFirst = new LockRequest().setExact(
        io.riverdb.tx.api.lock.LockScope.ROW, 23, 1, LockMode.EXCLUSIVE, 0);
    LockRequest cycleSecond = new LockRequest().setExact(
        io.riverdb.tx.api.lock.LockScope.ROW, 23, 2, LockMode.EXCLUSIVE, 0);
    LockToken ownerToken = new LockToken();
    LockToken grantedToken = new LockToken();
    LockExecutionLane lane = new LockExecutionLane();
    LockWaitHandle handle = new LockWaitHandle();
    LockToken cycleOwner = new LockToken();
    LockToken cycleVictim = new LockToken();
    LockToken cycleGranted = new LockToken();
    LockExecutionLane cycleOwnerLane = new LockExecutionLane();
    LockWaitHandle cycleOwnerWait = new LockWaitHandle();
    LockExecutionLane cycleVictimLane = new LockExecutionLane();
    LockWaitHandle cycleVictimWait = new LockWaitHandle();
    StatusDetail detail = new StatusDetail(64);

    for (int index = 0; index < 1_000; index++) {
      exercise(service, owner, waiter, request, ownerToken, grantedToken, lane, handle, detail);
      exerciseDeadlock(locks, service, owner, waiter, cycleFirst, cycleSecond,
          cycleOwner, cycleVictim, cycleGranted,
          cycleOwnerLane, cycleOwnerWait, cycleVictimLane, cycleVictimWait, detail);
    }
    long retained = locks.accountedBytes();
    long threadId = Thread.currentThread().threadId();
    long before = bean.getThreadAllocatedBytes(threadId);
    for (int index = 0; index < 10_000; index++) {
      exercise(service, owner, waiter, request, ownerToken, grantedToken, lane, handle, detail);
      exerciseDeadlock(locks, service, owner, waiter, cycleFirst, cycleSecond,
          cycleOwner, cycleVictim, cycleGranted,
          cycleOwnerLane, cycleOwnerWait, cycleVictimLane, cycleVictimWait, detail);
    }
    long allocated = bean.getThreadAllocatedBytes(threadId) - before;

    assertTrue(allocated <= 512, "warmed exact lock paths allocated bytes: " + allocated);
    assertEquals(retained, locks.accountedBytes());
    assertEquals(0, locks.activeLockCount());
    assertEquals(0, locks.waitingCount());
    LockDeadlockDiagnosticsSnapshot snapshot = new LockDeadlockDiagnosticsSnapshot(diagnostics);
    assertEquals(StatusCode.OK, locks.snapshotDeadlockDiagnostics(snapshot));
    assertEquals(11_000, snapshot.totalVictimSelections());
    assertEquals(1, snapshot.signatureCount());
    assertEquals(0, snapshot.victimEventOverflows());
  }

  private static void exercise(
      LockService service,
      TransactionContext owner,
      TransactionContext waiter,
      LockRequest request,
      LockToken ownerToken,
      LockToken grantedToken,
      LockExecutionLane lane,
      LockWaitHandle handle,
      StatusDetail detail) {
    allocationGuard += service.tryAcquire(
        owner, 1, request, 0, ownerToken, detail).ordinal();
    allocationGuard += service.enqueue(
        waiter, 1, 7, 1, request, 0, lane, handle, detail).ordinal();
    allocationGuard += service.release(owner, 1, ownerToken, detail).ordinal();
    allocationGuard += service.await(lane, handle, detail).ordinal();
    allocationGuard += service.consume(
        waiter, 1, lane, handle, grantedToken, detail).ordinal();
    allocationGuard += service.release(waiter, 1, grantedToken, detail).ordinal();
    allocationGuard += lane.reset().ordinal();
    allocationGuard += handle.reset().ordinal();

    allocationGuard += service.tryAcquire(
        owner, 1, request, 0, ownerToken, detail).ordinal();
    allocationGuard += service.enqueue(
        waiter, 1, 7, 1, request, 0, lane, handle, detail).ordinal();
    allocationGuard += service.cancel(lane, handle, detail).ordinal();
    allocationGuard += service.await(lane, handle, detail).ordinal();
    allocationGuard += service.release(owner, 1, ownerToken, detail).ordinal();
    allocationGuard += lane.reset().ordinal();
    allocationGuard += handle.reset().ordinal();
  }

  private static void exerciseDeadlock(
      LockManager locks,
      LockService service,
      TransactionContext owner,
      TransactionContext victim,
      LockRequest first,
      LockRequest second,
      LockToken ownerHeld,
      LockToken victimHeld,
      LockToken granted,
      LockExecutionLane ownerLane,
      LockWaitHandle ownerWait,
      LockExecutionLane victimLane,
      LockWaitHandle victimWait,
      StatusDetail detail) {
    allocationGuard += service.tryAcquire(
        owner, 1, first, 0, ownerHeld, detail).ordinal();
    allocationGuard += service.tryAcquire(
        victim, 1, second, 0, victimHeld, detail).ordinal();
    allocationGuard += service.enqueue(
        owner, 1, 8, 1, second, 0, ownerLane, ownerWait, detail).ordinal();
    allocationGuard += service.enqueue(
        victim, 1, 9, 1, first, 0, victimLane, victimWait, detail).ordinal();
    allocationGuard += service.await(victimLane, victimWait, detail).ordinal();
    allocationGuard += service.await(ownerLane, ownerWait, detail).ordinal();
    allocationGuard += service.consume(
        owner, 1, ownerLane, ownerWait, granted, detail).ordinal();
    allocationGuard += service.release(owner, 1, granted, detail).ordinal();
    allocationGuard += service.release(owner, 1, ownerHeld, detail).ordinal();
    allocationGuard += service.acknowledge(victimHeld, detail).ordinal();
    locks.exact.lifecycle.releaseAll(2, 1, StatusCode.CONFLICT);
    allocationGuard += ownerHeld.reset().ordinal();
    allocationGuard += victimHeld.reset().ordinal();
    allocationGuard += granted.reset().ordinal();
    allocationGuard += ownerLane.reset().ordinal();
    allocationGuard += ownerWait.reset().ordinal();
    allocationGuard += victimLane.reset().ordinal();
    allocationGuard += victimWait.reset().ordinal();
  }

  private static TransactionContext context(LockManager locks, long transactionId) {
    Object editor = new Object();
    TransactionContext context = new TransactionContext(
        editor, new TransactionSnapshot(2), new MutableCancellationToken());
    assertEquals(StatusCode.OK, context.bind(
        editor, locks.authority, 1, 2, transactionId, 1,
        transactionId, IsolationLevel.SERIALIZABLE));
    return context;
  }
}
