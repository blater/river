package io.riverdb.tx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import io.riverdb.tx.api.lock.LockExecutionLane;
import io.riverdb.tx.api.lock.LockMode;
import io.riverdb.tx.api.lock.LockRequest;
import io.riverdb.tx.api.lock.LockScope;
import io.riverdb.tx.api.lock.LockToken;
import io.riverdb.tx.api.lock.LockWaitHandle;
import org.junit.jupiter.api.Test;

final class LockBlockCausalityTest {
  @Test
  void activeOwnerUsesTheCanonicalHeldModeAndGrantPredicate() {
    Fixture fixture = new Fixture();
    assertEquals(StatusCode.OK, fixture.table.beginBlockCausalityCapture());
    LockToken owner = acquire(fixture, 1, key(10, LockMode.UPDATE));
    Wait wait = enqueue(fixture, 2, 1, key(10, LockMode.EXCLUSIVE));
    assertEquals(StatusCode.OK, fixture.table.release(owner));
    LockToken granted = consume(fixture, wait);
    assertEquals(StatusCode.OK, fixture.table.release(granted));

    LockBlockCausalitySnapshot snapshot = end(fixture);
    assertEquals(1, snapshot.bucketCount(
        LockScope.KEY, LockMode.EXCLUSIVE, LockMode.UPDATE, LockQueueKind.ORDINARY,
        LockDeadlockEdgeKind.ACTIVE_OWNER,
        LockGrantPrecondition.NO_INCOMPATIBLE_ACTIVE_OWNER));
    assertEquals(1, snapshot.actualBlocks());
    assertEquals(1, snapshot.blockedConsumed());
    assertEquals(1, snapshot.handoffs());
    int bucket = onlyBucket(snapshot);
    assertEquals(LockScope.KEY, snapshot.scopeAt(bucket));
    assertEquals(LockMode.EXCLUSIVE, snapshot.requestedModeAt(bucket));
    assertEquals(LockMode.UPDATE, snapshot.blockerModeAt(bucket));
    assertEquals(LockQueueKind.ORDINARY, snapshot.waiterQueueKindAt(bucket));
    assertEquals(LockQueueKind.ACTIVE_OWNER, snapshot.blockerQueueKindAt(bucket));
    assertEquals(LockDeadlockEdgeKind.ACTIVE_OWNER, snapshot.queueRelationshipAt(bucket));
    assertEquals(LockGrantPrecondition.NO_INCOMPATIBLE_ACTIVE_OWNER,
        snapshot.grantPreconditionAt(bucket));
    assertTrue(snapshot.reconciles());
    assertClean(fixture);
  }

  @Test
  void exactFifoHeadAndCancellationRemainDistinctFromSuccessfulHandoff() {
    Fixture fixture = new Fixture();
    assertEquals(StatusCode.OK, fixture.table.beginBlockCausalityCapture());
    LockToken owner = acquire(fixture, 1, row(20, LockMode.EXCLUSIVE));
    Wait first = enqueue(fixture, 2, 1, row(20, LockMode.EXCLUSIVE));
    Wait second = enqueue(fixture, 3, 1, row(20, LockMode.SHARED));
    assertEquals(StatusCode.CANCELLED,
        fixture.table.cancel(second.lane, second.handle, StatusCode.CANCELLED));
    assertEquals(StatusCode.OK, fixture.table.release(owner));
    LockToken granted = consume(fixture, first);
    assertEquals(StatusCode.OK, fixture.table.release(granted));

    LockBlockCausalitySnapshot snapshot = end(fixture);
    assertEquals(1, snapshot.bucketCount(
        LockScope.ROW, LockMode.SHARED, LockMode.EXCLUSIVE, LockQueueKind.ORDINARY,
        LockDeadlockEdgeKind.FIFO_FAIRNESS, LockGrantPrecondition.FIFO_QUEUE_HEAD));
    assertEquals(2, snapshot.actualBlocks());
    assertEquals(1, snapshot.blockedConsumed());
    assertEquals(1, snapshot.blockedCancelled());
    assertEquals(1, snapshot.cancelled());
    assertTrue(snapshot.reconciles());
    assertClean(fixture);
  }

  @Test
  void conversionPriorityRecordsBlockerRequestedModeAndCompletesBothHandoffs() {
    Fixture fixture = new Fixture();
    assertEquals(StatusCode.OK, fixture.table.beginBlockCausalityCapture());
    LockToken convertingOwner = acquire(fixture, 1, key(30, LockMode.SHARED));
    LockToken otherOwner = acquire(fixture, 2, key(30, LockMode.SHARED));
    Wait conversion = enqueue(fixture, 1, 1, key(30, LockMode.EXCLUSIVE));
    Wait reader = enqueue(fixture, 3, 1, key(30, LockMode.SHARED));

    assertEquals(StatusCode.OK, fixture.table.release(otherOwner));
    LockToken converted = consume(fixture, conversion);
    assertEquals(StatusCode.OK, fixture.table.release(convertingOwner));
    assertEquals(StatusCode.OK, fixture.table.release(converted));
    LockToken readerToken = consume(fixture, reader);
    assertEquals(StatusCode.OK, fixture.table.release(readerToken));

    LockBlockCausalitySnapshot snapshot = end(fixture);
    assertEquals(1, snapshot.bucketCount(
        LockScope.KEY, LockMode.SHARED, LockMode.EXCLUSIVE, LockQueueKind.ORDINARY,
        LockDeadlockEdgeKind.CONVERSION_PRIORITY,
        LockGrantPrecondition.CONVERSION_QUEUE_EMPTY));
    assertEquals(1, snapshot.bucketCount(
        LockScope.KEY, LockMode.EXCLUSIVE, LockMode.SHARED, LockQueueKind.CONVERSION,
        LockDeadlockEdgeKind.ACTIVE_OWNER,
        LockGrantPrecondition.NO_INCOMPATIBLE_ACTIVE_OWNER));
    assertEquals(2, snapshot.blockedConsumed());
    assertEquals(2, snapshot.handoffs());
    assertTrue(snapshot.reconciles());
    assertClean(fixture);
  }

  @Test
  void intervalFairnessUsesEarlierIncompatibleWaiterPredicate() {
    Fixture fixture = new Fixture();
    assertEquals(StatusCode.OK, fixture.table.beginBlockCausalityCapture());
    LockToken owner = acquire(fixture, 1, key(5, LockMode.SHARED));
    Wait writer = enqueue(fixture, 2, 1,
        new LockRequest().setRange(1, 0, 1, 10, LockMode.EXCLUSIVE, 0));
    Wait reader = enqueue(fixture, 3, 1, key(5, LockMode.SHARED));

    assertEquals(StatusCode.CANCELLED,
        fixture.table.cancel(reader.lane, reader.handle, StatusCode.CANCELLED));
    assertEquals(StatusCode.TIMEOUT,
        fixture.table.cancel(writer.lane, writer.handle, StatusCode.TIMEOUT));
    assertEquals(StatusCode.OK, fixture.table.release(owner));

    LockBlockCausalitySnapshot snapshot = end(fixture);
    assertEquals(1, snapshot.bucketCount(
        LockScope.KEY, LockMode.SHARED, LockMode.EXCLUSIVE, LockQueueKind.ORDINARY,
        LockDeadlockEdgeKind.FIFO_FAIRNESS,
        LockGrantPrecondition.NO_EARLIER_INCOMPATIBLE_WAITER));
    assertEquals(1, snapshot.blockedCancelled());
    assertEquals(1, snapshot.blockedTimedOut());
    assertTrue(snapshot.reconciles());
    assertClean(fixture);
  }

  @Test
  void deadlockVictimPhaseResetOverflowAndSnapshotCleanupAreExplicit() {
    Fixture fixture = new Fixture();
    assertEquals(StatusCode.OK, fixture.table.beginBlockCausalityCapture());
    LockToken left = acquire(fixture, 1, key(40, LockMode.EXCLUSIVE));
    LockToken right = acquire(fixture, 2, key(41, LockMode.EXCLUSIVE));
    Wait survivor = enqueue(fixture, 1, 1, key(41, LockMode.EXCLUSIVE));
    Wait victim = enqueue(
        fixture, 2, 1, key(40, LockMode.EXCLUSIVE), StatusCode.DEADLOCK);
    assertEquals(StatusCode.DEADLOCK, victim.handle.status());
    LockToken survivorGrant = consume(fixture, survivor);
    assertEquals(StatusCode.OK, fixture.table.release(left));
    assertEquals(StatusCode.OK, fixture.table.release(survivorGrant));

    LockBlockCausalitySnapshot first = end(fixture);
    assertEquals(1, first.victimSelections());
    assertEquals(1, first.deadlocked());
    assertEquals(1, first.actualBlocks());
    assertEquals(1, first.blockedConsumed());
    assertTrue(first.reconciles());
    assertClean(fixture);

    assertEquals(StatusCode.OK, fixture.table.beginBlockCausalityCapture());
    LockBlockCausalitySnapshot second = end(fixture);
    assertEquals(0, second.entered());
    assertEquals(0, second.actualBlocks());
    assertTrue(second.reconciles());

    LockBlockCausality limited = new LockBlockCausality(1);
    assertEquals(StatusCode.OK, limited.begin());
    limited.entered();
    limited.entered();
    LockBlockCausalitySnapshot overflow = new LockBlockCausalitySnapshot();
    assertEquals(StatusCode.INVARIANT_BROKEN, limited.end(overflow));
    assertEquals(1, overflow.entered());
    assertEquals(1, overflow.overflows());
    assertFalse(overflow.reconciles());

    TransactionManager manager = new TransactionManager(3, 5, 1, 1);
    Transaction transaction = new Transaction(1);
    assertEquals(StatusCode.OK,
        manager.begin(IsolationLevel.SERIALIZABLE, 0, transaction));
    assertEquals(1, manager.retainedSnapshotCount());
    assertEquals(StatusCode.OK, manager.abort(transaction, new TransactionOutcome()));
    assertEquals(0, manager.retainedSnapshotCount());
    assertEquals(0, manager.activeTransactionCount());
    assertEquals(0, manager.activeLockCount());
    assertEquals(0, manager.waitingLockCount());
  }

  private static LockToken acquire(Fixture fixture, long transaction, LockRequest request) {
    LockToken token = new LockToken();
    assertEquals(StatusCode.OK,
        fixture.table.tryAcquire(transaction, 1, transaction, request, token));
    return token;
  }

  private static Wait enqueue(
      Fixture fixture, long transaction, long laneId, LockRequest request) {
    return enqueue(fixture, transaction, laneId, request, StatusCode.RETRY);
  }

  private static Wait enqueue(
      Fixture fixture, long transaction, long laneId,
      LockRequest request, StatusCode expected) {
    Wait wait = new Wait();
    assertEquals(expected, fixture.table.enqueue(
        transaction, 1, transaction, laneId, 1, request, wait.lane, wait.handle, 1));
    return wait;
  }

  private static LockToken consume(Fixture fixture, Wait wait) {
    LockToken token = new LockToken();
    assertEquals(StatusCode.OK, fixture.table.consume(wait.lane, wait.handle, token));
    return token;
  }

  private static LockBlockCausalitySnapshot end(Fixture fixture) {
    LockBlockCausalitySnapshot snapshot = new LockBlockCausalitySnapshot();
    assertEquals(StatusCode.OK, fixture.table.endBlockCausalityCapture(snapshot));
    return snapshot;
  }

  private static void assertClean(Fixture fixture) {
    assertEquals(0, fixture.table.holdingCount());
    assertEquals(0, fixture.table.waitingCount());
  }

  private static int onlyBucket(LockBlockCausalitySnapshot snapshot) {
    int found = -1;
    for (int index = 0; index < snapshot.bucketCapacity(); index++) {
      if (snapshot.bucketCountAt(index) == 0) continue;
      assertEquals(-1, found);
      found = index;
    }
    assertTrue(found >= 0);
    return found;
  }

  private static LockRequest key(long key, LockMode mode) {
    return new LockRequest().setKey(1, key, mode, 0);
  }

  private static LockRequest row(long row, LockMode mode) {
    return new LockRequest().setExact(LockScope.ROW, 1, row, mode, 0);
  }

  private static final class Wait {
    final LockExecutionLane lane = new LockExecutionLane();
    final LockWaitHandle handle = new LockWaitHandle();
  }

  private static final class Fixture {
    final LockExactTable table = new LockExactTable(
        new Object(), 83, new LockSegmentArena(new LockMemoryEnvelope(32L << 20)));
  }
}
