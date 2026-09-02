package io.riverdb.tx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

final class LockExactPressureTest {
  private static final long TEST_ENVELOPE_BYTES = 32L << 20;

  @Test
  void compoundEnqueueRestoresEveryTypedStoreAndIndexGrowthFailure() {
    Growth growth = growth();
    long[] boundaries = {
        growth.resource,
        growth.transaction,
        growth.request,
        growth.holding,
        growth.index,
        growth.index,
        growth.index,
        growth.index
    };
    long admitted = growth.baseline;
    for (long boundary : boundaries) {
      assertFailedEnqueueRestores(admitted + boundary - 1, growth.baseline);
      admitted += boundary;
    }
  }

  @Test
  void intervalCompoundAdmissionRestoresEveryIndexSegmentFailure() {
    Growth growth = growth();
    long[] boundaries = {
        growth.resource,
        growth.transaction,
        growth.request,
        growth.holding,
        growth.index,
        growth.interval,
        growth.index,
        growth.index,
        growth.index
    };
    long admitted = growth.baseline;
    for (long boundary : boundaries) {
      assertFailedEnqueueRestores(
          admitted + boundary - 1, growth.baseline, rangeRequest());
      admitted += boundary;
    }
  }

  @Test
  void laneIndexConservesEntriesAcrossGrantConsumeCancelAndReleaseAll() {
    Fixture fixture = new Fixture(TEST_ENVELOPE_BYTES);
    consumeRemovesLaneOnlyAfterGrantIsClaimed(fixture);
    explicitCancelRemovesLaneBeforeCarrierAcknowledgement(fixture);
    releaseAllRemovesLaneBeforeCarrierAcknowledgement(fixture);
    assertEquals(0, fixture.table.holdingCount());
    assertEquals(0, fixture.table.waitingCount());
  }

  @Test
  void terminalCancellationReusesDistinctQueuedIntervalResources() {
    Fixture fixture = new Fixture(TEST_ENVELOPE_BYTES);
    LockToken owner = new LockToken();
    LockRequest wide = new LockRequest().setRange(
        61, 0, 61, 10_000, LockMode.EXCLUSIVE, 0);
    assertEquals(StatusCode.OK, fixture.table.tryAcquire(1, 1, 1, wide, owner));
    long retained = -1;
    for (long key = 1; key <= 512; key++) {
      LockRequest request = new LockRequest().setKey(61, key, LockMode.EXCLUSIVE, 0);
      LockExecutionLane lane = new LockExecutionLane();
      LockWaitHandle handle = new LockWaitHandle();
      assertEquals(StatusCode.RETRY, fixture.table.enqueue(
          key + 1, 1, key + 1, 1, 1, request, lane, handle));
      fixture.table.lifecycle.releaseAll(key + 1, 1, StatusCode.CANCELLED);
      assertEquals(-1, fixture.table.state.directory.resource(request));
      assertEquals(StatusCode.CANCELLED, fixture.table.acknowledge(lane, handle));
      if (retained < 0) retained = fixture.arena.accountedBytes();
      else assertEquals(retained, fixture.arena.accountedBytes());
    }
    assertEquals(StatusCode.OK, fixture.table.release(owner));
    assertEquals(0, fixture.table.waitingCount());
    assertEquals(0, fixture.table.holdingCount());
  }

  private static void assertFailedEnqueueRestores(long maximumBytes, long baseline) {
    assertFailedEnqueueRestores(maximumBytes, baseline, request(71));
  }

  private static void assertFailedEnqueueRestores(
      long maximumBytes, long baseline, LockRequest request) {
    Fixture fixture = new Fixture(maximumBytes);
    LockExecutionLane lane = new LockExecutionLane();
    LockWaitHandle handle = new LockWaitHandle();
    assertEquals(StatusCode.RESOURCE_EXHAUSTED,
        fixture.table.enqueue(2, 3, 2, 5, 7, request, lane, handle));
    assertEquals(baseline, fixture.arena.accountedBytes());
    assertEquals(-1, fixture.table.state.directory.resource(request));
    assertEquals(-1, fixture.table.state.directory.transaction(2, 3));
    assertEquals(-1, fixture.table.state.directory.lane(2, 3, 5, 7));
    assertEquals(0, fixture.table.holdingCount());
    assertEquals(0, fixture.table.waitingCount());
    assertEquals(1, fixture.table.nextCapability);
    assertEquals(1, fixture.table.nextReference);
    assertEquals(1, fixture.table.nextRequest);
    assertFalse(lane.isPending());
    assertEquals(LockWaitState.IDLE, handle.state());
  }

  private static void consumeRemovesLaneOnlyAfterGrantIsClaimed(Fixture fixture) {
    LockRequest request = request(81);
    LockToken owner = new LockToken();
    LockToken granted = new LockToken();
    LockExecutionLane lane = new LockExecutionLane();
    LockWaitHandle handle = new LockWaitHandle();
    assertEquals(StatusCode.OK,
        fixture.table.tryAcquire(10, 1, 10, request, owner));
    assertEquals(StatusCode.RETRY,
        fixture.table.enqueue(11, 1, 11, 1, 1, request, lane, handle));
    long retained = fixture.arena.accountedBytes();
    assertTrue(fixture.table.state.directory.lane(11, 1, 1, 1) >= 0);
    assertEquals(StatusCode.OK, fixture.table.release(owner));
    assertEquals(LockWaitState.GRANTED, handle.state());
    assertTrue(fixture.table.state.directory.lane(11, 1, 1, 1) >= 0);
    assertEquals(StatusCode.OK, fixture.table.consume(lane, handle, granted));
    assertEquals(-1, fixture.table.state.directory.lane(11, 1, 1, 1));
    assertFalse(lane.isPending());
    assertEquals(StatusCode.OK, fixture.table.release(granted));
    assertEquals(retained, fixture.arena.accountedBytes());
    assertEquals(StatusCode.OK, lane.reset());
    assertEquals(StatusCode.OK, handle.reset());
  }

  private static void explicitCancelRemovesLaneBeforeCarrierAcknowledgement(Fixture fixture) {
    LockRequest request = request(82);
    LockToken owner = new LockToken();
    LockExecutionLane lane = new LockExecutionLane();
    LockWaitHandle handle = new LockWaitHandle();
    assertEquals(StatusCode.OK,
        fixture.table.tryAcquire(20, 1, 20, request, owner));
    assertEquals(StatusCode.RETRY,
        fixture.table.enqueue(21, 1, 21, 2, 1, request, lane, handle));
    long retained = fixture.arena.accountedBytes();
    assertTrue(fixture.table.state.directory.lane(21, 1, 2, 1) >= 0);
    assertEquals(StatusCode.CANCELLED,
        fixture.table.cancel(lane, handle, StatusCode.CANCELLED));
    assertEquals(-1, fixture.table.state.directory.lane(21, 1, 2, 1));
    assertTrue(lane.isPending());
    assertEquals(LockWaitState.CANCELLED, handle.state());
    assertEquals(StatusCode.CANCELLED, fixture.table.acknowledge(lane, handle));
    assertFalse(lane.isPending());
    assertEquals(StatusCode.OK, fixture.table.release(owner));
    assertEquals(retained, fixture.arena.accountedBytes());
    assertEquals(StatusCode.OK, lane.reset());
    assertEquals(StatusCode.OK, handle.reset());
  }

  private static void releaseAllRemovesLaneBeforeCarrierAcknowledgement(Fixture fixture) {
    LockRequest request = request(83);
    LockToken owner = new LockToken();
    LockExecutionLane lane = new LockExecutionLane();
    LockWaitHandle handle = new LockWaitHandle();
    assertEquals(StatusCode.OK,
        fixture.table.tryAcquire(30, 1, 30, request, owner));
    assertEquals(StatusCode.RETRY,
        fixture.table.enqueue(31, 1, 31, 3, 1, request, lane, handle));
    long retained = fixture.arena.accountedBytes();
    assertTrue(fixture.table.state.directory.lane(31, 1, 3, 1) >= 0);
    fixture.table.lifecycle.releaseAll(31, 1, StatusCode.CONFLICT);
    assertEquals(-1, fixture.table.state.directory.lane(31, 1, 3, 1));
    assertTrue(lane.isPending());
    assertEquals(LockWaitState.FAILED, handle.state());
    assertEquals(StatusCode.CONFLICT, fixture.table.acknowledge(lane, handle));
    assertFalse(lane.isPending());
    assertEquals(StatusCode.OK, fixture.table.release(owner));
    assertEquals(retained, fixture.arena.accountedBytes());
    assertEquals(StatusCode.OK, lane.reset());
    assertEquals(StatusCode.OK, handle.reset());
  }

  private static Growth growth() {
    Fixture fixture = new Fixture(TEST_ENVELOPE_BYTES);
    long baseline = fixture.arena.accountedBytes();
    long resource = typedGrowth(fixture, fixture.table.state.resources, baseline);
    long transaction = typedGrowth(fixture, fixture.table.state.transactions, baseline);
    long request = typedGrowth(fixture, fixture.table.state.requests, baseline);
    long holding = typedGrowth(fixture, fixture.table.state.holdings, baseline);
    long index = 4 * LockLongStore.FIRST_SEGMENT_GROWTH_BYTES;
    long interval = 5 * LockLongStore.FIRST_SEGMENT_GROWTH_BYTES;
    return new Growth(baseline, resource, transaction, request, holding, index, interval);
  }

  private static long typedGrowth(
      Fixture fixture, LockTypedSlots store, long baseline) {
    LockSlotReservation reservation = new LockSlotReservation();
    assertEquals(StatusCode.OK, store.reserve(reservation));
    long growth = fixture.arena.accountedBytes() - baseline;
    store.rollback(reservation);
    assertEquals(baseline, fixture.arena.accountedBytes());
    return growth;
  }

  private static LockRequest request(long identity) {
    return new LockRequest().setExact(
        LockScope.ROW, 41, identity, LockMode.EXCLUSIVE, 0);
  }

  private static LockRequest rangeRequest() {
    return new LockRequest().setRange(
        41, 70, 41, 72, LockMode.EXCLUSIVE, 0);
  }

  private static final class Fixture {
    final LockSegmentArena arena;
    final LockExactTable table;

    Fixture(long maximumBytes) {
      arena = new LockSegmentArena(new LockMemoryEnvelope(maximumBytes));
      table = new LockExactTable(new Object(), 73, arena);
    }
  }

  private record Growth(
      long baseline,
      long resource,
      long transaction,
      long request,
      long holding,
      long index,
      long interval) {
  }
}
