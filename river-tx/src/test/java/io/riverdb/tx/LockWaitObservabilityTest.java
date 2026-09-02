package io.riverdb.tx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.riverdb.base.error.StatusCode;
import io.riverdb.tx.api.lock.LockExecutionLane;
import io.riverdb.tx.api.lock.LockMode;
import io.riverdb.tx.api.lock.LockRequest;
import io.riverdb.tx.api.lock.LockToken;
import io.riverdb.tx.api.lock.LockWaitHandle;
import org.junit.jupiter.api.Test;

final class LockWaitObservabilityTest {
  @Test
  void countsGrantTimeoutAndCancellationWithoutCrossManagerBleed() {
    Fixture fixture = new Fixture();
    Fixture other = new Fixture();
    LockToken owner = new LockToken();
    assertEquals(StatusCode.OK,
        fixture.table.tryAcquire(1, 1, 1, key(10, LockMode.EXCLUSIVE), owner));

    Wait granted = enqueue(fixture, 2, 1, 2, key(10, LockMode.EXCLUSIVE));
    assertEquals(1, fixture.table.lockWaitsEntered());
    assertEquals(0, fixture.table.lockWaitsGranted());
    assertEquals(StatusCode.OK, fixture.table.release(owner));
    assertEquals(1, fixture.table.lockWaitsGranted());
    assertEquals(StatusCode.OK,
        fixture.table.consume(granted.lane, granted.handle, new LockToken()));

    LockToken secondOwner = new LockToken();
    assertEquals(StatusCode.OK,
        fixture.table.tryAcquire(3, 1, 3, key(11, LockMode.EXCLUSIVE), secondOwner));
    Wait timedOut = enqueue(fixture, 4, 1, 4, key(11, LockMode.EXCLUSIVE));
    assertEquals(StatusCode.TIMEOUT,
        fixture.table.cancel(timedOut.lane, timedOut.handle, StatusCode.TIMEOUT));
    Wait cancelled = enqueue(fixture, 5, 1, 5, key(11, LockMode.EXCLUSIVE));
    assertEquals(StatusCode.CANCELLED,
        fixture.table.cancel(cancelled.lane, cancelled.handle, StatusCode.CANCELLED));

    assertEquals(3, fixture.table.lockWaitsEntered());
    assertEquals(1, fixture.table.lockWaitsGranted());
    assertEquals(1, fixture.table.lockWaitsTimedOut());
    assertEquals(0, fixture.table.lockWaitsDeadlocked());
    assertEquals(1, fixture.table.lockWaitsCancelled());
    assertEquals(0, other.table.lockWaitsEntered());
    assertEquals(0, other.table.lockWaitsGranted());
  }

  @Test
  void countsEachDeadlockedWaitAndReportsEscalationAsUnsupported() {
    Fixture fixture = new Fixture();
    LockToken first = new LockToken();
    LockToken second = new LockToken();
    assertEquals(StatusCode.OK,
        fixture.table.tryAcquire(1, 1, 1, key(20, LockMode.EXCLUSIVE), first));
    assertEquals(StatusCode.OK,
        fixture.table.tryAcquire(2, 1, 2, key(21, LockMode.EXCLUSIVE), second));
    enqueue(fixture, 1, 1, 1, key(21, LockMode.EXCLUSIVE));
    Wait victim = enqueue(fixture, 2, 1, 2, key(20, LockMode.EXCLUSIVE));

    assertEquals(StatusCode.DEADLOCK, victim.status());
    assertEquals(2, fixture.table.lockWaitsEntered());
    assertEquals(1, fixture.table.lockWaitsGranted());
    assertEquals(1, fixture.table.lockWaitsDeadlocked());
    assertFalse(LockWaitCounters.escalationSupported());
    assertEquals(0, LockWaitCounters.escalationCount());
  }

  private static Wait enqueue(
      Fixture fixture, long transaction, long generation, long laneId, LockRequest request) {
    Wait wait = new Wait();
    StatusCode status = fixture.table.enqueue(
        transaction, generation, transaction, laneId, 1, request, wait.lane, wait.handle);
    if (status != StatusCode.RETRY && status != StatusCode.OK
        && status != StatusCode.DEADLOCK) {
      throw new AssertionError("unexpected enqueue status: " + status);
    }
    return wait;
  }

  private static LockRequest key(long key, LockMode mode) {
    return new LockRequest().setKey(1, key, mode, 0);
  }

  private static final class Wait {
    final LockExecutionLane lane = new LockExecutionLane();
    final LockWaitHandle handle = new LockWaitHandle();

    StatusCode status() { return handle.status(); }
  }

  private static final class Fixture {
    final LockSegmentArena arena = new LockSegmentArena(new LockMemoryEnvelope(32L << 20));
    final LockExactTable table = new LockExactTable(new Object(), 73, arena);
  }
}
