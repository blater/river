package io.riverdb.tx;

import static io.riverdb.tx.LockTupleIntervalTableTestSupport.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.tx.LockTupleIntervalTableTestSupport.Fixture;
import io.riverdb.tx.LockTupleIntervalTableTestSupport.Wait;
import com.sun.management.ThreadMXBean;
import io.riverdb.base.error.StatusCode;
import io.riverdb.tx.api.lock.LockMode;
import io.riverdb.tx.api.lock.LockRequest;
import io.riverdb.tx.api.lock.LockToken;
import io.riverdb.tx.api.lock.LockWaitState;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

final class LockTupleIntervalTableTest {
  @Test
  void emptyWaiterReleaseSkipsOverlapSearchWithoutLosingALaterWake() {
    Fixture fixture = new Fixture();
    LockRequest request = prefix(6, bytes(4), LockMode.EXCLUSIVE);
    LockToken firstOwner = acquire(fixture, 1, request);
    long searches = fixture.table.overlapSearches();

    assertEquals(StatusCode.OK, fixture.table.release(firstOwner));
    assertEquals(searches, fixture.table.overlapSearches());

    LockToken secondOwner = acquire(fixture, 2, request);
    Wait waiter = enqueue(fixture, 3, 1, request, StatusCode.RETRY);
    assertEquals(LockWaitState.QUEUED, waiter.handle.state());
    assertEquals(StatusCode.OK, fixture.table.release(secondOwner));
    assertEquals(LockWaitState.GRANTED, waiter.handle.state());
    assertEquals(StatusCode.OK, fixture.table.release(consume(fixture, waiter)));
    assertEquals(0, fixture.table.waitingCount());
    assertEquals(0, fixture.table.holdingCount());
  }

  @Test
  void cancellingTheLastWaiterSkipsOverlapSearchAndStillRecyclesTheResource() {
    Fixture fixture = new Fixture();
    LockRequest request = prefix(6, bytes(7), LockMode.EXCLUSIVE);
    LockToken owner = acquire(fixture, 1, request);
    Wait waiter = enqueue(fixture, 2, 1, request, StatusCode.RETRY);
    long searches = fixture.table.overlapSearches();

    assertEquals(StatusCode.CANCELLED,
        fixture.table.cancel(waiter.lane, waiter.handle, StatusCode.CANCELLED));
    assertEquals(searches, fixture.table.overlapSearches());
    assertEquals(0, fixture.table.waitingCount());
    assertEquals(StatusCode.OK, fixture.table.release(owner));
    assertEquals(searches, fixture.table.overlapSearches());
    assertEquals(0, fixture.table.holdingCount());
    assertEquals(-1, fixture.table.state.directory.resource(request));
  }

  @Test
  void cancellingAQueuedWriterSearchesAndGrantsItsCompatibleSuccessor() {
    Fixture fixture = new Fixture();
    LockRequest shared = prefix(6, bytes(9), LockMode.SHARED);
    LockToken owner = acquire(fixture, 1, shared);
    Wait writer = enqueue(
        fixture, 2, 1, prefix(6, bytes(9), LockMode.EXCLUSIVE), StatusCode.RETRY);
    Wait reader = enqueue(fixture, 3, 1, shared, StatusCode.RETRY);
    assertEquals(LockWaitState.QUEUED, writer.handle.state());
    assertEquals(LockWaitState.QUEUED, reader.handle.state());
    long searches = fixture.table.overlapSearches();

    assertEquals(StatusCode.CANCELLED,
        fixture.table.cancel(writer.lane, writer.handle, StatusCode.CANCELLED));
    assertTrue(fixture.table.overlapSearches() > searches);
    assertEquals(LockWaitState.GRANTED, reader.handle.state());
    assertEquals(StatusCode.OK, fixture.table.release(consume(fixture, reader)));
    assertEquals(StatusCode.OK, fixture.table.release(owner));
    assertEquals(0, fixture.table.waitingCount());
    assertEquals(0, fixture.table.holdingCount());
  }

  @Test
  void exactAndRangeConflictInBothOrdersWithHalfOpenBoundaries() {
    Fixture fixture = new Fixture();
    LockToken range = acquire(fixture, 1, range(7, bytes(10), true, bytes(20), false,
        LockMode.SHARED));
    assertEquals(StatusCode.RETRY, tryAcquire(fixture, 2, key(7, bytes(15))));
    LockToken upper = acquire(fixture, 3, key(7, bytes(20)));
    assertEquals(StatusCode.OK, fixture.table.release(upper));
    assertEquals(StatusCode.OK, fixture.table.release(range));

    LockToken exact = acquire(fixture, 4, key(7, bytes(15)));
    assertEquals(StatusCode.RETRY, tryAcquire(fixture, 5,
        range(7, bytes(10), true, bytes(20), false, LockMode.SHARED)));
    assertEquals(StatusCode.OK, fixture.table.release(exact));
  }

  @Test
  void inclusivePrefixCoversDescendantsWhileExclusiveCutsDoNot() {
    Fixture fixture = new Fixture();
    LockToken prefix = acquire(fixture, 1,
        range(8, bytes(3), true, bytes(3), true, LockMode.SHARED));
    assertEquals(StatusCode.RETRY, tryAcquire(fixture, 2, key(8, bytes(3))));
    assertEquals(StatusCode.RETRY, tryAcquire(fixture, 3, key(8, bytes(3, 0))));
    LockToken neighbor = acquire(fixture, 4, key(8, bytes(4)));
    assertEquals(StatusCode.OK, fixture.table.release(neighbor));
    assertEquals(StatusCode.OK, fixture.table.release(prefix));

    LockToken descendantsExcluded = acquire(fixture, 5,
        range(8, bytes(3), false, bytes(4), false, LockMode.SHARED));
    LockToken samePrefix = acquire(fixture, 6, key(8, bytes(3, 9)));
    assertEquals(StatusCode.OK, fixture.table.release(samePrefix));
    assertEquals(StatusCode.OK, fixture.table.release(descendantsExcluded));
  }

  @Test
  void overlappingUpdatePrefixesSerializeWhileCompatibleAndDisjointRequestsProceed() {
    Fixture fixture = new Fixture();
    LockToken updateOwner = acquire(
        fixture, 1, prefix(17, bytes(3), LockMode.UPDATE));
    LockToken sharedOwner = acquire(
        fixture, 2, prefix(17, bytes(3), LockMode.SHARED));
    LockToken disjointUpdate = acquire(
        fixture, 3, prefix(17, bytes(4), LockMode.UPDATE));
    Wait overlappingUpdate = enqueue(
        fixture, 4, 1, prefix(17, bytes(3), LockMode.UPDATE), StatusCode.RETRY);
    Wait descendantWriter = enqueue(
        fixture, 5, 1, key(17, bytes(3, 7), LockMode.EXCLUSIVE), StatusCode.RETRY);

    assertEquals(LockWaitState.QUEUED, overlappingUpdate.handle.state());
    assertEquals(LockWaitState.QUEUED, descendantWriter.handle.state());
    assertEquals(0, fixture.table.deadlockVictimSelections());

    assertEquals(StatusCode.OK, fixture.table.release(updateOwner));
    assertEquals(LockWaitState.GRANTED, overlappingUpdate.handle.state());
    assertEquals(LockWaitState.QUEUED, descendantWriter.handle.state());
    LockToken grantedUpdate = consume(fixture, overlappingUpdate);
    assertEquals(StatusCode.OK, fixture.table.release(grantedUpdate));
    assertEquals(LockWaitState.QUEUED, descendantWriter.handle.state());
    assertEquals(StatusCode.OK, fixture.table.release(sharedOwner));
    assertEquals(LockWaitState.GRANTED, descendantWriter.handle.state());
    assertEquals(StatusCode.OK, fixture.table.release(consume(fixture, descendantWriter)));
    assertEquals(StatusCode.OK, fixture.table.release(disjointUpdate));
    assertEquals(0, fixture.table.deadlockVictimSelections());
  }

  @Test
  void updatePrefixOwnerBypassesPredecessorsItBlocksThenPreservesSchedulerOrder() {
    Fixture fixture = new Fixture();
    LockToken prefixOwner = acquire(
        fixture, 1, prefix(18, bytes(6), LockMode.UPDATE));
    Wait descendantWriter = enqueue(
        fixture, 3, 1, key(18, bytes(6, 2), LockMode.EXCLUSIVE), StatusCode.RETRY);
    Wait overlappingUpdate = enqueue(
        fixture, 2, 1, prefix(18, bytes(6), LockMode.UPDATE), StatusCode.RETRY);

    LockToken ownerWriter = acquire(
        fixture, 1, key(18, bytes(6, 2), LockMode.EXCLUSIVE));
    assertEquals(LockWaitState.QUEUED, descendantWriter.handle.state());
    assertEquals(LockWaitState.QUEUED, overlappingUpdate.handle.state());
    assertEquals(0, fixture.table.deadlockVictimSelections());

    assertEquals(StatusCode.OK, fixture.table.release(ownerWriter));
    assertEquals(LockWaitState.QUEUED, descendantWriter.handle.state());
    assertEquals(LockWaitState.QUEUED, overlappingUpdate.handle.state());
    assertEquals(StatusCode.OK, fixture.table.release(prefixOwner));
    assertEquals(LockWaitState.GRANTED, descendantWriter.handle.state());
    assertEquals(LockWaitState.QUEUED, overlappingUpdate.handle.state());
    assertEquals(StatusCode.OK, fixture.table.release(consume(fixture, descendantWriter)));
    assertEquals(LockWaitState.GRANTED, overlappingUpdate.handle.state());
    assertEquals(StatusCode.OK, fixture.table.release(consume(fixture, overlappingUpdate)));
    assertEquals(0, fixture.table.deadlockVictimSelections());
  }

  @Test
  void lateSharedPrefixDoesNotBargeQueuedDescendantWriter() {
    Fixture fixture = new Fixture();
    LockToken prefixOwner = acquire(
        fixture, 1, prefix(19, bytes(8), LockMode.UPDATE));
    Wait descendantWriter = enqueue(
        fixture, 2, 1, key(19, bytes(8, 4), LockMode.EXCLUSIVE), StatusCode.RETRY);

    assertEquals(StatusCode.RETRY, tryAcquire(
        fixture, 3, prefix(19, bytes(8), LockMode.SHARED)));
    Wait lateReader = enqueue(
        fixture, 3, 1, prefix(19, bytes(8), LockMode.SHARED), StatusCode.RETRY);
    assertEquals(LockWaitState.QUEUED, descendantWriter.handle.state());
    assertEquals(LockWaitState.QUEUED, lateReader.handle.state());

    assertEquals(StatusCode.OK, fixture.table.release(prefixOwner));
    assertEquals(LockWaitState.GRANTED, descendantWriter.handle.state());
    assertEquals(LockWaitState.QUEUED, lateReader.handle.state());
    assertEquals(StatusCode.OK, fixture.table.release(consume(fixture, descendantWriter)));
    assertEquals(LockWaitState.GRANTED, lateReader.handle.state());
    assertEquals(StatusCode.OK, fixture.table.release(consume(fixture, lateReader)));
    assertEquals(0, fixture.table.deadlockVictimSelections());
  }

  @Test
  void nullBoundsCoverOnlyTheirTupleNamespace() {
    Fixture fixture = new Fixture();
    LockToken namespace = acquire(fixture, 1,
        range(Long.MIN_VALUE, null, false, null, false, LockMode.SHARED));
    assertEquals(StatusCode.RETRY,
        tryAcquire(fixture, 2, key(Long.MIN_VALUE, bytes(99))));
    LockToken other = acquire(fixture, 3, key(Long.MIN_VALUE + 1, bytes(99)));
    assertEquals(StatusCode.OK, fixture.table.release(other));
    assertEquals(StatusCode.OK, fixture.table.release(namespace));
  }

  @Test
  void invalidTupleSlicesAndInvertedPrefixRangesAreRejected() {
    Fixture fixture = new Fixture();
    LockRequest invalidSlice = new LockRequest().setTupleKey(
        1, bytes(1), 1, 1, LockMode.EXCLUSIVE, 0);
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        tryAcquire(fixture, 1, invalidSlice));
    LockRequest inverted = range(
        1, bytes(20), true, bytes(10), true, LockMode.EXCLUSIVE);
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        tryAcquire(fixture, 2, inverted));
  }

  @Test
  void releaseGrantsOverlappingTupleWaiterAndTimeoutRecyclesResource() {
    Fixture fixture = new Fixture();
    LockToken owner = acquire(fixture, 1,
        range(9, bytes(1), true, bytes(9), false, LockMode.SHARED));
    Wait wait = enqueue(fixture, 2, 1, key(9, bytes(5)), StatusCode.RETRY);
    assertEquals(StatusCode.TIMEOUT,
        fixture.table.cancel(wait.lane, wait.handle, StatusCode.TIMEOUT));
    assertEquals(StatusCode.TIMEOUT, fixture.table.acknowledge(wait.lane, wait.handle));
    assertEquals(-1, fixture.table.state.directory.resource(key(9, bytes(5))));

    Wait next = enqueue(fixture, 3, 1, key(9, bytes(5)), StatusCode.RETRY);
    assertEquals(StatusCode.OK, fixture.table.release(owner));
    assertEquals(LockWaitState.GRANTED, next.handle.state());
    LockToken granted = new LockToken();
    assertEquals(StatusCode.OK, fixture.table.consume(next.lane, next.handle, granted));
    assertEquals(StatusCode.OK, fixture.table.release(granted));
  }

  @Test
  void tupleAndExactResourceCycleUsesUnifiedDeadlockGraph() {
    Fixture fixture = new Fixture();
    LockToken row = acquire(fixture, 1, row(17));
    LockToken tuple = acquire(fixture, 2, key(10, bytes(5)));
    Wait survivor = enqueue(fixture, 1, 1, key(10, bytes(5)), StatusCode.RETRY);
    Wait victim = enqueue(fixture, 2, 1, row(17), StatusCode.DEADLOCK);

    assertTrue(fixture.table.deadlocked(2, 1));
    assertEquals(LockWaitState.DEADLOCK, victim.handle.state());
    assertEquals(LockWaitState.GRANTED, survivor.handle.state());
    assertEquals(StatusCode.DEADLOCK, fixture.table.acknowledge(victim.lane, victim.handle));
    assertEquals(StatusCode.NOT_OWNER, fixture.table.acknowledge(tuple));
    LockToken granted = new LockToken();
    assertEquals(StatusCode.OK, fixture.table.consume(survivor.lane, survivor.handle, granted));
    assertEquals(StatusCode.OK, fixture.table.release(granted));
    assertEquals(StatusCode.OK, fixture.table.release(row));
  }

  @Test
  void tupleBytesAreCopiedAndReusedWithoutWarmedAllocation() {
    java.lang.management.ThreadMXBean standard = ManagementFactory.getThreadMXBean();
    Assumptions.assumeTrue(standard instanceof ThreadMXBean);
    ThreadMXBean bean = (ThreadMXBean) standard;
    Assumptions.assumeTrue(bean.isThreadAllocatedMemorySupported());
    bean.setThreadAllocatedMemoryEnabled(true);
    Fixture fixture = new Fixture();
    ByteBuffer bytes = bytes(1, 2, 3, 4);
    LockRequest request = key(11, bytes);
    LockToken token = new LockToken();
    for (int index = 0; index < 1_000; index++) cycle(fixture, request, token);
    long retained = fixture.arena.accountedBytes();
    long thread = Thread.currentThread().threadId();
    long before = bean.getThreadAllocatedBytes(thread);
    for (int index = 0; index < 10_000; index++) cycle(fixture, request, token);
    long allocated = bean.getThreadAllocatedBytes(thread) - before;

    assertTrue(allocated <= 256, "warmed tuple lock path allocated bytes: " + allocated);
    assertEquals(retained, fixture.arena.accountedBytes());
  }

  @Test
  void canonicalTupleBytesDoNotAliasBorrowedRequestBuffer() {
    Fixture fixture = new Fixture();
    byte[] mutable = {1, 2};
    LockRequest original = key(12, ByteBuffer.wrap(mutable));
    LockToken owner = acquire(fixture, 1, original);
    long resource = fixture.table.state.directory.resource(original);
    LockExactResourceStore.Chunk chunk = fixture.table.state.resources.record(resource);
    byte[] retained = chunk.tupleLowerBytes[LockTypedSlots.offset(resource)];
    mutable[0] = 9;
    assertEquals(StatusCode.RETRY,
        tryAcquire(fixture, 2, key(12, ByteBuffer.wrap(new byte[] {1, 2}))));
    LockToken different = acquire(
        fixture, 3, key(12, ByteBuffer.wrap(new byte[] {9, 2})));
    assertEquals(StatusCode.OK, fixture.table.release(different));
    assertEquals(StatusCode.OK, fixture.table.release(owner));
    assertEquals(0, retained[0]);
    assertEquals(0, retained[1]);
  }

  @Test
  void tupleBytePressureReturnsResourceExhaustedWithoutLostAccounting() {
    LockRequest large = key(13, ByteBuffer.allocate(8_192));
    LockSegmentArena roomyArena = new LockSegmentArena(new LockMemoryEnvelope(1L << 20));
    LockExactResourceStore roomy = new LockExactResourceStore(roomyArena);
    LockSlotReservation roomySlot = reserveCommitted(roomy);
    long baseline = roomyArena.accountedBytes();
    assertEquals(StatusCode.OK, roomy.prepareTuple(roomySlot.slot, large));
    long byteGrowth = roomyArena.accountedBytes() - baseline;

    LockSegmentArena pressuredArena =
        new LockSegmentArena(new LockMemoryEnvelope(baseline + byteGrowth - 1));
    LockExactResourceStore pressured = new LockExactResourceStore(pressuredArena);
    LockSlotReservation pressuredSlot = reserveCommitted(pressured);
    assertEquals(baseline, pressuredArena.accountedBytes());
    assertEquals(StatusCode.RESOURCE_EXHAUSTED,
        pressured.prepareTuple(pressuredSlot.slot, large));
    assertEquals(baseline, pressuredArena.accountedBytes());
  }

  @Test
  void twoEndpointPreparationIsAtomicAtBytePressureBoundary() {
    LockRequest range = range(14, ByteBuffer.allocate(8_192), true,
        ByteBuffer.allocate(8_192), true, LockMode.SHARED);
    LockSegmentArena roomyArena = new LockSegmentArena(new LockMemoryEnvelope(1L << 20));
    LockExactResourceStore roomy = new LockExactResourceStore(roomyArena);
    LockSlotReservation roomySlot = reserveCommitted(roomy);
    long baseline = roomyArena.accountedBytes();
    assertEquals(StatusCode.OK, roomy.prepareTuple(roomySlot.slot, range));
    long byteGrowth = roomyArena.accountedBytes() - baseline;

    LockSegmentArena pressuredArena =
        new LockSegmentArena(new LockMemoryEnvelope(baseline + byteGrowth - 1));
    LockExactResourceStore pressured = new LockExactResourceStore(pressuredArena);
    LockSlotReservation pressuredSlot = reserveCommitted(pressured);
    assertEquals(StatusCode.RESOURCE_EXHAUSTED,
        pressured.prepareTuple(pressuredSlot.slot, range));
    assertEquals(baseline, pressuredArena.accountedBytes());
    LockExactResourceStore.Chunk chunk = pressured.record(pressuredSlot.slot);
    int offset = LockTypedSlots.offset(pressuredSlot.slot);
    assertEquals(null, chunk.tupleLowerBytes[offset]);
    assertEquals(null, chunk.tupleUpperBytes[offset]);
  }

  @Test
  void tupleOverlapLookupIsLogarithmicPlusReturnedOverlaps() {
    Fixture fixture = new Fixture();
    for (int value = 0; value < 4_095; value++) {
      acquire(fixture, 1, new LockRequest().setTupleKey(
          15, ordered(value), 0, 2, LockMode.SHARED, 0));
    }
    LockIntervalCursor cursor = new LockIntervalCursor();
    LockRequest query = new LockRequest().setTupleKey(
        15, ordered(2_048), 0, 2, LockMode.SHARED, 0);
    assertEquals(StatusCode.OK, fixture.table.state.intervals.overlaps(query, cursor));
    long matches = 0;
    while (cursor.next() >= 0) matches++;
    assertEquals(1, matches);
    assertTrue(cursor.visits() <= 64, "tuple point lookup rescanned the interval tree");
  }

  @Test
  void warmedTupleReactiveGrantAllocatesNoSteadyStateMemory() {
    java.lang.management.ThreadMXBean standard = ManagementFactory.getThreadMXBean();
    Assumptions.assumeTrue(standard instanceof ThreadMXBean);
    ThreadMXBean bean = (ThreadMXBean) standard;
    Assumptions.assumeTrue(bean.isThreadAllocatedMemorySupported());
    bean.setThreadAllocatedMemoryEnabled(true);
    Fixture fixture = new Fixture();
    LockRequest request = key(16, bytes(7, 8, 9));
    LockToken owner = new LockToken();
    LockToken granted = new LockToken();
    Wait wait = new Wait();
    for (int index = 0; index < 1_000; index++) {
      reactiveCycle(fixture, request, owner, granted, wait);
    }
    long retained = fixture.arena.accountedBytes();
    long thread = Thread.currentThread().threadId();
    long before = bean.getThreadAllocatedBytes(thread);
    for (int index = 0; index < 10_000; index++) {
      reactiveCycle(fixture, request, owner, granted, wait);
    }
    long allocated = bean.getThreadAllocatedBytes(thread) - before;
    assertTrue(allocated <= 256, "warmed tuple reactive path allocated bytes: " + allocated);
    assertEquals(retained, fixture.arena.accountedBytes());
  }

}
