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
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

final class LockExactDeadlockTest {
  @Test
  void twoTransactionCycleVictimBreaksCycleAndGrantsSurvivor() {
    Fixture fixture = new Fixture();
    LockToken firstA = acquire(fixture, 1, 10);
    LockToken secondB = acquire(fixture, 2, 20);
    Wait first = enqueue(fixture, 1, 1, 20, StatusCode.RETRY);
    Wait second = enqueue(fixture, 2, 1, 10, StatusCode.DEADLOCK);
    assertTrue(fixture.table.deadlocked(2, 1));
    assertEquals(1, fixture.table.deadlockVictimSelections());
    fixture.table.lifecycle.freeze(2, 1);
    assertTrue(fixture.table.deadlocked(2, 1));
    assertTrue(fixture.table.lifecycle.frozen(2, 1));
    assertEquals(LockWaitState.DEADLOCK, second.handle.state());
    assertEquals(LockWaitState.GRANTED, first.handle.state());
    assertEquals(StatusCode.DEADLOCK,
        fixture.table.tryAcquire(2, 1, 2, request(30), new LockToken()));
    assertEquals(StatusCode.DEADLOCK, fixture.table.acknowledge(second.lane, second.handle));
    assertEquals(StatusCode.NOT_OWNER, fixture.table.acknowledge(secondB));
    assertEquals(StatusCode.OK, fixture.table.release(firstA));
  }

  @Test
  void threeTransactionCycleSelectsYoungestSuppliedBeginOrder() {
    Fixture fixture = new Fixture();
    acquire(fixture, 20, 3, 20);
    acquire(fixture, 30, 2, 30);
    acquire(fixture, 10, 1, 10);
    Wait oldest = enqueue(fixture, 10, 1, 1, 20, StatusCode.RETRY);
    Wait youngest = enqueue(fixture, 20, 3, 1, 30, StatusCode.RETRY);
    Wait middle = enqueue(fixture, 30, 2, 1, 10, StatusCode.RETRY);
    assertTrue(fixture.table.lifecycle.deadlocked(20, 1));
    assertEquals(LockWaitState.DEADLOCK, youngest.handle.state());
    assertEquals(LockWaitState.GRANTED, oldest.handle.state());
    assertEquals(LockWaitState.QUEUED, middle.handle.state());
  }

  @Test
  void multipleLanesUnionEdgesAndIgnoreUnrelatedYoungerTransaction() {
    Fixture fixture = new Fixture();
    acquire(fixture, 1, 10);
    acquire(fixture, 2, 20);
    acquire(fixture, 3, 30);
    enqueue(fixture, 1, 1, 20, StatusCode.RETRY);
    enqueue(fixture, 1, 2, 30, StatusCode.RETRY);
    Wait second = enqueue(fixture, 2, 1, 10, StatusCode.DEADLOCK);
    assertTrue(fixture.table.lifecycle.deadlocked(2, 1));
    assertEquals(LockWaitState.DEADLOCK, second.handle.state());
    assertTrue(!fixture.table.lifecycle.deadlocked(3, 1));
  }

  @Test
  void sameTransactionFifoPredecessorDoesNotCreateSelfCycle() {
    Fixture fixture = new Fixture();
    acquire(fixture, 1, 10);
    Wait first = enqueue(fixture, 2, 1, 10, StatusCode.RETRY);
    Wait second = enqueue(fixture, 2, 2, 10, StatusCode.RETRY);
    assertTrue(!fixture.table.lifecycle.deadlocked(2, 1));
    assertEquals(LockWaitState.QUEUED, first.handle.state());
    assertEquals(LockWaitState.QUEUED, second.handle.state());
  }

  @Test
  void activeOwnerCycleDoesNotSelectAYoungerFifoConvoyWaiter() {
    Fixture fixture = new Fixture();
    LockToken warehouseOwner = acquireTuple(fixture, 1, 1, 101, 1, LockMode.EXCLUSIVE);
    acquireTuple(fixture, 2, 2, 102, 1, LockMode.EXCLUSIVE);
    Wait convoy = enqueueTuple(
        fixture, 3, 3, 1, 101, 1, LockMode.EXCLUSIVE, StatusCode.RETRY);
    Wait districtOwner = enqueueTuple(
        fixture, 2, 2, 1, 101, 1, LockMode.SHARED, StatusCode.RETRY);

    Wait warehouse = enqueueTuple(
        fixture, 1, 1, 1, 102, 1, LockMode.EXCLUSIVE, StatusCode.OK);

    assertTrue(fixture.table.lifecycle.deadlocked(2, 1));
    assertTrue(!fixture.table.lifecycle.deadlocked(3, 1));
    assertEquals(LockWaitState.DEADLOCK, districtOwner.handle.state());
    assertEquals(LockWaitState.QUEUED, convoy.handle.state());
    assertEquals(LockWaitState.GRANTED, warehouse.handle.state());
    assertEquals(1, fixture.table.deadlockVictimSelections());
    assertEquals(StatusCode.OK, fixture.table.release(warehouseOwner));
    assertEquals(LockWaitState.GRANTED, convoy.handle.state());
  }

  @Test
  void compatibleReaderStillWaitsForFifoPredecessorAndFormsEdge() {
    Fixture fixture = new Fixture();
    acquire(fixture, 1, 10, LockMode.SHARED);
    acquire(fixture, 2, 20);
    acquire(fixture, 3, 30);
    enqueue(fixture, 2, 1, 10, LockMode.EXCLUSIVE, StatusCode.RETRY);
    enqueue(fixture, 2, 2, 30, LockMode.EXCLUSIVE, StatusCode.RETRY);
    Wait third = enqueue(fixture, 3, 1, 10, LockMode.SHARED, StatusCode.DEADLOCK);
    assertTrue(fixture.table.lifecycle.deadlocked(3, 1));
    assertEquals(LockWaitState.DEADLOCK, third.handle.state());
  }

  @Test
  void compatibleImmediatePredecessorRetainsTheFifoDependencyChain() {
    Fixture fixture = new Fixture();
    acquire(fixture, 1, 1, 10, LockMode.SHARED);
    acquire(fixture, 4, 4, 20, LockMode.EXCLUSIVE);
    enqueue(fixture, 2, 2, 1, 10, LockMode.EXCLUSIVE, StatusCode.RETRY);
    enqueue(fixture, 3, 3, 1, 10, LockMode.SHARED, StatusCode.RETRY);
    Wait youngest = enqueue(
        fixture, 4, 4, 1, 10, LockMode.SHARED, StatusCode.RETRY);

    Wait second = enqueue(
        fixture, 2, 2, 2, 20, LockMode.EXCLUSIVE, StatusCode.OK);

    assertTrue(fixture.table.lifecycle.deadlocked(4, 1));
    assertEquals(LockWaitState.DEADLOCK, youngest.handle.state());
    assertEquals(LockWaitState.GRANTED, second.handle.state());
  }

  @Test
  void headRequestEnumeratesMultipleOwnersAndFindsCycle() {
    Fixture fixture = new Fixture();
    acquire(fixture, 1, 10, LockMode.SHARED);
    acquire(fixture, 2, 10, LockMode.SHARED);
    acquire(fixture, 3, 20);
    enqueue(fixture, 1, 1, 20, StatusCode.RETRY);
    Wait third = enqueue(fixture, 3, 1, 10, StatusCode.DEADLOCK);
    assertTrue(fixture.table.lifecycle.deadlocked(3, 1));
    assertEquals(LockWaitState.DEADLOCK, third.handle.state());
  }

  @Test
  void transactionWorkspacePressureRollsBackAndDeadlockSlotCanBeReused() {
    Fixture sizing = new Fixture();
    long baseline = sizing.arena.accountedBytes();
    long resourceGrowth = typedGrowth(sizing, sizing.table.state.resources, baseline);
    long transactionGrowth = typedGrowth(sizing, sizing.table.state.transactions, baseline);
    Fixture pressured = new Fixture(baseline + resourceGrowth + transactionGrowth - 1);
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, pressured.table.enqueue(
        9, 1, 9, 1, 1, request(90), new LockExecutionLane(), new LockWaitHandle()));
    assertEquals(baseline, pressured.arena.accountedBytes());
    assertEquals(-1, pressured.table.state.directory.transaction(9, 1));

    Fixture fixture = new Fixture();
    acquire(fixture, 1, 10);
    acquire(fixture, 2, 20);
    enqueue(fixture, 1, 1, 20, StatusCode.RETRY);
    enqueue(fixture, 2, 1, 10, StatusCode.DEADLOCK);
    long victimSlot = fixture.table.state.directory.transaction(2, 1);
    fixture.table.lifecycle.releaseAll(2, 1, StatusCode.DEADLOCK);
    assertEquals(-1, fixture.table.state.directory.transaction(2, 1));
    acquire(fixture, 4, 40);
    assertEquals(victimSlot, fixture.table.state.directory.transaction(4, 1));
    assertTrue(!fixture.table.lifecycle.deadlocked(4, 1));
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

  private static LockToken acquire(Fixture fixture, long transaction, long identity) {
    return acquire(fixture, transaction, identity, LockMode.EXCLUSIVE);
  }

  private static LockToken acquire(
      Fixture fixture, long transaction, long identity, LockMode mode) {
    return acquire(fixture, transaction, transaction, identity, mode);
  }

  private static LockToken acquire(
      Fixture fixture, long transaction, long startOrder, long identity) {
    return acquire(fixture, transaction, startOrder, identity, LockMode.EXCLUSIVE);
  }

  private static LockToken acquire(
      Fixture fixture, long transaction, long startOrder, long identity, LockMode mode) {
    LockToken token = new LockToken();
    assertEquals(StatusCode.OK,
        fixture.table.tryAcquire(transaction, 1, startOrder, request(identity, mode), token));
    return token;
  }

  private static Wait enqueue(
      Fixture fixture, long transaction, long lane, long identity, StatusCode expected) {
    return enqueue(fixture, transaction, lane, identity, LockMode.EXCLUSIVE, expected);
  }

  private static Wait enqueue(
      Fixture fixture, long transaction, long lane, long identity,
      LockMode mode, StatusCode expected) {
    return enqueue(
        fixture, transaction, transaction, lane, identity, mode, expected);
  }

  private static Wait enqueue(
      Fixture fixture, long transaction, long startOrder, long lane, long identity,
      StatusCode expected) {
    return enqueue(
        fixture, transaction, startOrder, lane, identity, LockMode.EXCLUSIVE, expected);
  }

  private static Wait enqueue(
      Fixture fixture, long transaction, long startOrder, long lane, long identity,
      LockMode mode, StatusCode expected) {
    Wait wait = new Wait();
    assertEquals(expected, fixture.table.enqueue(
        transaction, 1, startOrder, lane, 1,
        request(identity, mode), wait.lane, wait.handle));
    return wait;
  }

  private static LockRequest request(long identity) {
    return request(identity, LockMode.EXCLUSIVE);
  }

  private static LockRequest request(long identity, LockMode mode) {
    return new LockRequest().setExact(LockScope.ROW, 7, identity, mode, 0);
  }

  private static LockToken acquireTuple(
      Fixture fixture, long transaction, long startOrder,
      long namespace, int value, LockMode mode) {
    LockToken token = new LockToken();
    assertEquals(StatusCode.OK, fixture.table.tryAcquire(
        transaction, 1, startOrder, tuple(namespace, value, mode), token));
    return token;
  }

  private static Wait enqueueTuple(
      Fixture fixture, long transaction, long startOrder, long lane,
      long namespace, int value, LockMode mode, StatusCode expected) {
    Wait wait = new Wait();
    assertEquals(expected, fixture.table.enqueue(
        transaction, 1, startOrder, lane, 1,
        tuple(namespace, value, mode), wait.lane, wait.handle));
    return wait;
  }

  private static LockRequest tuple(long namespace, int value, LockMode mode) {
    ByteBuffer bytes = ByteBuffer.wrap(new byte[] {(byte) value});
    return new LockRequest().setTupleKey(namespace, bytes, 0, 1, mode, 0);
  }

  private static final class Wait {
    final LockExecutionLane lane = new LockExecutionLane();
    final LockWaitHandle handle = new LockWaitHandle();
  }

  private static final class Fixture {
    final LockSegmentArena arena;
    final LockExactTable table;

    Fixture() { this(32L << 20); }

    Fixture(long maximumBytes) {
      arena = new LockSegmentArena(new LockMemoryEnvelope(maximumBytes));
      table = new LockExactTable(new Object(), 91, arena);
    }
  }
}
