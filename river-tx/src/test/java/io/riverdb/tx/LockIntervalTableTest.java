package io.riverdb.tx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.tx.api.lock.LockExecutionLane;
import io.riverdb.tx.api.lock.LockMode;
import io.riverdb.tx.api.lock.LockRequest;
import io.riverdb.tx.api.lock.LockScope;
import io.riverdb.tx.api.lock.LockToken;
import io.riverdb.tx.api.lock.LockWaitHandle;
import io.riverdb.tx.api.lock.LockWaitState;
import org.junit.jupiter.api.Test;

final class LockIntervalTableTest {
  @Test
  void keyAndRangeConflictInBothAdmissionOrdersAndRespectUpperBoundary() {
    Fixture fixture = new Fixture();
    LockToken range = acquire(fixture, 1, range(1, 10, 1, 20, LockMode.SHARED));
    assertEquals(StatusCode.RETRY, fixture.table.tryAcquire(
        2, 1, 2, key(1, 15, LockMode.EXCLUSIVE), new LockToken()));
    LockToken boundary = acquire(fixture, 3, key(1, 20, LockMode.EXCLUSIVE));
    assertEquals(StatusCode.OK, fixture.table.release(boundary));
    assertEquals(StatusCode.OK, fixture.table.release(range));

    LockToken point = acquire(fixture, 4, key(1, 15, LockMode.EXCLUSIVE));
    assertEquals(StatusCode.RETRY, fixture.table.tryAcquire(
        5, 1, 5, range(1, 10, 1, 20, LockMode.SHARED), new LockToken()));
    assertEquals(StatusCode.OK, fixture.table.release(point));
  }

  @Test
  void overlappingReleaseReactivelyGrantsOnlyTheEligibleWaiter() {
    Fixture fixture = new Fixture();
    LockToken owner = acquire(
        fixture, 1, range(2, 0, 2, 100, LockMode.SHARED));
    Wait writer = enqueue(
        fixture, 2, 1, key(2, 50, LockMode.EXCLUSIVE), StatusCode.RETRY);
    Wait lateReader = enqueue(
        fixture, 3, 1, range(2, 40, 2, 60, LockMode.SHARED), StatusCode.RETRY);
    assertEquals(LockWaitState.QUEUED, writer.handle.state());
    assertEquals(LockWaitState.QUEUED, lateReader.handle.state());

    assertEquals(StatusCode.OK, fixture.table.release(owner));
    assertEquals(LockWaitState.GRANTED, writer.handle.state());
    assertEquals(LockWaitState.QUEUED, lateReader.handle.state());
    LockToken writerToken = consume(fixture, writer);
    assertEquals(StatusCode.OK, fixture.table.release(writerToken));
    assertEquals(LockWaitState.GRANTED, lateReader.handle.state());
    LockToken readerToken = consume(fixture, lateReader);
    assertEquals(StatusCode.OK, fixture.table.release(readerToken));
    assertEquals(2, fixture.table.targetedWakes());
  }

  @Test
  void compatibleOverlappingReadersGrantAsOneReactiveCohort() {
    Fixture fixture = new Fixture();
    LockToken owner = acquire(
        fixture, 1, range(3, 0, 3, 100, LockMode.EXCLUSIVE));
    Wait first = enqueue(
        fixture, 2, 1, range(3, 10, 3, 40, LockMode.SHARED), StatusCode.RETRY);
    Wait second = enqueue(
        fixture, 3, 1, key(3, 20, LockMode.SHARED), StatusCode.RETRY);
    assertEquals(StatusCode.OK, fixture.table.release(owner));
    assertEquals(LockWaitState.GRANTED, first.handle.state());
    assertEquals(LockWaitState.GRANTED, second.handle.state());
    assertEquals(StatusCode.OK, fixture.table.release(consume(fixture, first)));
    assertEquals(StatusCode.OK, fixture.table.release(consume(fixture, second)));
  }

  @Test
  void timedOutPointAndDisjointPointLifecyclePreserveOwnedRange() {
    Fixture fixture = new Fixture();
    LockToken range = acquire(
        fixture, 1, range(2, 10, 2, 20, LockMode.SHARED));
    Wait timedOut = enqueue(
        fixture, 2, 1, key(2, 12, LockMode.EXCLUSIVE), StatusCode.RETRY);
    assertEquals(
        StatusCode.TIMEOUT,
        fixture.table.cancel(timedOut.lane, timedOut.handle, StatusCode.TIMEOUT));
    fixture.table.lifecycle.releaseAll(2, 1, StatusCode.TIMEOUT);

    LockToken outside = acquire(fixture, 3, key(2, 25, LockMode.EXCLUSIVE));
    assertEquals(StatusCode.OK, fixture.table.release(outside));
    assertEquals(StatusCode.RETRY, fixture.table.tryAcquire(
        4, 1, 4, key(2, 15, LockMode.EXCLUSIVE), new LockToken()));
    assertEquals(StatusCode.OK, fixture.table.release(range));
  }

  @Test
  void ownOverlappingHoldingDoesNotSelfBlock() {
    Fixture fixture = new Fixture();
    LockToken range = acquire(
        fixture, 1, range(4, 0, 4, 100, LockMode.SHARED));
    LockToken key = acquire(fixture, 1, key(4, 50, LockMode.EXCLUSIVE));
    assertEquals(StatusCode.OK, fixture.table.release(key));
    assertEquals(StatusCode.OK, fixture.table.release(range));
  }

  @Test
  void overlappingWaitersFromOneTransactionDoNotStrandEachOther() {
    Fixture fixture = new Fixture();
    LockToken owner = acquire(
        fixture, 9, range(6, 0, 6, 100, LockMode.EXCLUSIVE));
    Wait earlier = enqueue(
        fixture, 1, 1, range(6, 50, 6, 100, LockMode.EXCLUSIVE), StatusCode.RETRY);
    Wait later = enqueue(
        fixture, 1, 2, range(6, 0, 6, 60, LockMode.EXCLUSIVE), StatusCode.RETRY);

    assertEquals(StatusCode.OK, fixture.table.release(owner));
    assertEquals(LockWaitState.GRANTED, earlier.handle.state());
    assertEquals(LockWaitState.GRANTED, later.handle.state());
    assertEquals(StatusCode.OK, fixture.table.release(consume(fixture, earlier)));
    assertEquals(StatusCode.OK, fixture.table.release(consume(fixture, later)));
  }

  @Test
  void mixedExactAndIntervalCycleUsesOneGraphAndGrantsSurvivor() {
    Fixture fixture = new Fixture();
    LockToken exact = acquire(fixture, 1, row(7, 9));
    LockToken interval = acquire(
        fixture, 2, range(5, 10, 5, 20, LockMode.EXCLUSIVE));
    Wait survivor = enqueue(
        fixture, 1, 1, key(5, 15, LockMode.EXCLUSIVE), StatusCode.RETRY);
    long firstTransaction = fixture.table.state.directory.transaction(1, 1);
    long secondTransaction = fixture.table.state.directory.transaction(2, 1);
    long intervalRequest = survivor.lane.requestSlot();
    long requestedResource = fixture.table.state.requests.record(intervalRequest)
        .resources[LockTypedSlots.offset(intervalRequest)];
    long overlap = fixture.table.state.intervals.firstOverlap(requestedResource);
    boolean foundSecondOwner = false;
    while (overlap >= 0) {
      long holding = LockTypedSlots.decode(fixture.table.state.resources.record(overlap)
          .ownerHeads[LockTypedSlots.offset(overlap)]);
      while (holding >= 0) {
        LockExactHoldingStore.Chunk holdings = fixture.table.state.holdings.record(holding);
        int offset = LockTypedSlots.offset(holding);
        if (holdings.transactions[offset] == secondTransaction) foundSecondOwner = true;
        holding = LockTypedSlots.decode(holdings.nextResource[offset]);
      }
      overlap = fixture.table.state.intervals.nextOverlap(requestedResource, overlap);
    }
    assertTrue(foundSecondOwner);
    LockExactBlockerCursor blockers = new LockExactBlockerCursor(fixture.table);
    blockers.begin(firstTransaction);
    assertEquals(secondTransaction, blockers.next(firstTransaction));
    Wait victim = enqueue(fixture, 2, 1, row(7, 9), StatusCode.DEADLOCK);

    assertTrue(fixture.table.deadlocked(2, 1));
    assertEquals(LockWaitState.DEADLOCK, victim.handle.state());
    assertEquals(LockWaitState.GRANTED, survivor.handle.state());
    assertEquals(StatusCode.DEADLOCK, fixture.table.acknowledge(victim.lane, victim.handle));
    assertEquals(StatusCode.NOT_OWNER, fixture.table.acknowledge(interval));
    assertEquals(StatusCode.OK, fixture.table.release(consume(fixture, survivor)));
    assertEquals(StatusCode.OK, fixture.table.release(exact));
    fixture.table.lifecycle.releaseAll(2, 1, StatusCode.DEADLOCK);
  }

  @Test
  void fairnessGraphTraversesPastSameTransactionModeHeadToForeignWaiter() {
    Fixture fixture = new Fixture();
    LockToken intervalOwner = acquire(
        fixture, 9, range(8, 0, 8, 100, LockMode.EXCLUSIVE));
    Wait sameTransactionHead = enqueue(
        fixture, 1, 1, range(8, 50, 8, 100, LockMode.EXCLUSIVE), StatusCode.RETRY);
    Wait foreign = enqueue(
        fixture, 2, 1, range(8, 50, 8, 100, LockMode.EXCLUSIVE), StatusCode.RETRY);
    Wait current = enqueue(
        fixture, 1, 2, range(8, 0, 8, 60, LockMode.EXCLUSIVE), StatusCode.RETRY);

    assertTrue(fixture.table.deadlocked(2, 1));
    assertEquals(LockWaitState.DEADLOCK, foreign.handle.state());
    assertEquals(StatusCode.DEADLOCK, fixture.table.acknowledge(foreign.lane, foreign.handle));
    assertEquals(StatusCode.OK, fixture.table.release(intervalOwner));
    assertEquals(StatusCode.OK, fixture.table.release(consume(fixture, sameTransactionHead)));
    assertEquals(StatusCode.OK, fixture.table.release(consume(fixture, current)));
  }

  private static LockToken acquire(Fixture fixture, long transaction, LockRequest request) {
    LockToken token = new LockToken();
    assertEquals(StatusCode.OK,
        fixture.table.tryAcquire(transaction, 1, transaction, request, token));
    return token;
  }

  private static Wait enqueue(
      Fixture fixture, long transaction, long lane, LockRequest request, StatusCode expected) {
    Wait wait = new Wait();
    assertEquals(expected, fixture.table.enqueue(
        transaction, 1, transaction, lane, 1, request, wait.lane, wait.handle));
    return wait;
  }

  private static LockToken consume(Fixture fixture, Wait wait) {
    LockToken token = new LockToken();
    assertEquals(StatusCode.OK, fixture.table.consume(wait.lane, wait.handle, token));
    return token;
  }

  private static LockRequest row(long high, long low) {
    return new LockRequest().setExact(
        LockScope.ROW, high, low, LockMode.EXCLUSIVE, 0);
  }

  private static LockRequest key(long space, long key, LockMode mode) {
    return new LockRequest().setKey(space, key, mode, 0);
  }

  private static LockRequest range(
      long lowerSpace, long lowerKey, long upperSpace, long upperKey, LockMode mode) {
    return new LockRequest().setRange(
        lowerSpace, lowerKey, upperSpace, upperKey, mode, 0);
  }

  private static final class Wait {
    final LockExecutionLane lane = new LockExecutionLane();
    final LockWaitHandle handle = new LockWaitHandle();
  }

  private static final class Fixture {
    final LockExactTable table = new LockExactTable(
        new Object(), 97,
        new LockSegmentArena(new LockMemoryEnvelope(32L << 20)));
  }
}
