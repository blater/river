package io.riverdb.tx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.tx.api.lock.LockExecutionLane;
import io.riverdb.tx.api.lock.LockMode;
import io.riverdb.tx.api.lock.LockRequest;
import io.riverdb.tx.api.lock.LockToken;
import io.riverdb.tx.api.lock.LockScope;
import io.riverdb.tx.api.lock.LockWaitHandle;
import io.riverdb.tx.api.lock.LockWaitState;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

final class LockExactTableTest {
  @Test
  void activeOwnerConvertsAheadOfAnOrdinaryWaiter() {
    Fixture fixture = new Fixture();
    LockToken shared = new LockToken();
    assertEquals(StatusCode.OK,
        fixture.table.tryAcquire(1, 1, 1, key(50, LockMode.SHARED), shared));
    LockExecutionLane waiterLane = new LockExecutionLane();
    LockWaitHandle waiter = new LockWaitHandle();
    assertEquals(StatusCode.RETRY, fixture.table.enqueue(
        2, 1, 2, 1, 1, key(50, LockMode.EXCLUSIVE), waiterLane, waiter));

    LockToken converted = new LockToken();
    assertEquals(StatusCode.OK,
        fixture.table.tryAcquire(1, 1, 1, key(50, LockMode.EXCLUSIVE), converted));
    assertEquals(0, fixture.table.deadlockVictimSelections());
    assertEquals(LockWaitState.QUEUED, waiter.state());
    assertEquals(StatusCode.OK, fixture.table.release(shared));
    assertEquals(StatusCode.OK, fixture.table.release(converted));
    assertEquals(LockWaitState.GRANTED, waiter.state());
  }

  @Test
  void queuedConversionWaitsForAnotherOwnerThenPrecedesOrdinaryWaiter() {
    Fixture fixture = new Fixture();
    LockToken first = new LockToken();
    LockToken second = new LockToken();
    assertEquals(StatusCode.OK,
        fixture.table.tryAcquire(1, 1, 1, key(51, LockMode.SHARED), first));
    assertEquals(StatusCode.OK,
        fixture.table.tryAcquire(2, 1, 2, key(51, LockMode.SHARED), second));
    LockExecutionLane ordinaryLane = new LockExecutionLane();
    LockWaitHandle ordinary = new LockWaitHandle();
    assertEquals(StatusCode.RETRY, fixture.table.enqueue(
        3, 1, 3, 1, 1, key(51, LockMode.EXCLUSIVE), ordinaryLane, ordinary));
    LockExecutionLane conversionLane = new LockExecutionLane();
    LockWaitHandle conversion = new LockWaitHandle();
    assertEquals(StatusCode.RETRY, fixture.table.enqueue(
        1, 1, 1, 1, 1, key(51, LockMode.EXCLUSIVE), conversionLane, conversion));

    assertEquals(StatusCode.OK, fixture.table.release(second));
    assertEquals(LockWaitState.GRANTED, conversion.state());
    assertEquals(LockWaitState.QUEUED, ordinary.state());
    LockToken converted = new LockToken();
    assertEquals(StatusCode.OK,
        fixture.table.consume(conversionLane, conversion, converted));
    assertEquals(StatusCode.OK, fixture.table.release(first));
    assertEquals(StatusCode.OK, fixture.table.release(converted));
    assertEquals(LockWaitState.GRANTED, ordinary.state());
  }

  @Test
  void twoQueuedConversionsRetainGenuineDeadlockDetection() {
    Fixture fixture = new Fixture();
    LockToken first = new LockToken();
    LockToken second = new LockToken();
    assertEquals(StatusCode.OK,
        fixture.table.tryAcquire(1, 1, 1, key(52, LockMode.SHARED), first));
    assertEquals(StatusCode.OK,
        fixture.table.tryAcquire(2, 1, 2, key(52, LockMode.SHARED), second));
    LockExecutionLane firstLane = new LockExecutionLane();
    LockWaitHandle firstConversion = new LockWaitHandle();
    assertEquals(StatusCode.RETRY, fixture.table.enqueue(
        1, 1, 1, 1, 1, key(52, LockMode.EXCLUSIVE), firstLane, firstConversion));
    LockExecutionLane secondLane = new LockExecutionLane();
    LockWaitHandle secondConversion = new LockWaitHandle();
    assertEquals(StatusCode.DEADLOCK, fixture.table.enqueue(
        2, 1, 2, 1, 1, key(52, LockMode.EXCLUSIVE), secondLane, secondConversion));

    assertEquals(1, fixture.table.deadlockVictimSelections());
    assertEquals(LockWaitState.GRANTED, firstConversion.state());
    assertEquals(LockWaitState.DEADLOCK, secondConversion.state());
  }

  @Test
  void mixedModeConversionFifoCannotStrandACompatibleTail() {
    Fixture fixture = new Fixture();
    LockToken firstShared = new LockToken();
    LockToken secondShared = new LockToken();
    LockToken updateOwner = new LockToken();
    assertEquals(StatusCode.OK,
        fixture.table.tryAcquire(1, 1, 1, key(55, LockMode.SHARED), firstShared));
    assertEquals(StatusCode.OK,
        fixture.table.tryAcquire(2, 1, 2, key(55, LockMode.SHARED), secondShared));
    assertEquals(StatusCode.OK,
        fixture.table.tryAcquire(3, 1, 3, key(55, LockMode.UPDATE), updateOwner));
    LockExecutionLane exclusiveLane = new LockExecutionLane();
    LockWaitHandle exclusive = new LockWaitHandle();
    assertEquals(StatusCode.RETRY, fixture.table.enqueue(
        1, 1, 1, 1, 1, key(55, LockMode.EXCLUSIVE), exclusiveLane, exclusive));
    LockExecutionLane updateLane = new LockExecutionLane();
    LockWaitHandle update = new LockWaitHandle();

    assertEquals(StatusCode.DEADLOCK, fixture.table.enqueue(
        2, 1, 2, 1, 1, key(55, LockMode.UPDATE), updateLane, update));
    assertEquals(LockWaitState.DEADLOCK, update.state());
    assertEquals(StatusCode.OK, fixture.table.release(updateOwner));
    assertEquals(LockWaitState.GRANTED, exclusive.state());
  }

  @Test
  void cancellingAConversionDetectsTheCycleCreatedByCompatiblePrefixGrant() {
    LockRequest shared = key(58, LockMode.SHARED);
    LockRequest update = key(58, LockMode.UPDATE);
    assertConversionRemovalDetectsCycle(
        shared, update, shared, key(58, LockMode.EXCLUSIVE), StatusCode.CANCELLED);
  }

  @Test
  void timingOutAnOverlappingConversionDetectsTheCycleCreatedByItsRemoval() {
    LockRequest rangeShared = new LockRequest().setRange(
        12, 0, 12, 10, LockMode.SHARED, 0);
    LockRequest rangeUpdate = new LockRequest().setRange(
        12, 0, 12, 10, LockMode.UPDATE, 0);
    assertConversionRemovalDetectsCycle(
        rangeShared, rangeUpdate,
        new LockRequest().setKey(12, 5, LockMode.SHARED, 0),
        new LockRequest().setKey(12, 5, LockMode.EXCLUSIVE, 0),
        StatusCode.TIMEOUT);
  }

  @Test
  void directOverlappingModeUpgradeDetectsTheCycleItCreates() {
    Fixture fixture = new Fixture();
    LockRequest xShared = new LockRequest().setRange(
        13, 0, 13, 5, LockMode.SHARED, 0);
    LockRequest yShared = new LockRequest().setRange(
        13, 4, 13, 8, LockMode.SHARED, 0);
    LockRequest yUpdate = new LockRequest().setRange(
        13, 4, 13, 8, LockMode.UPDATE, 0);
    LockToken xOwner = new LockToken();
    LockToken yOwner = new LockToken();
    LockToken zOwner = new LockToken();
    assertEquals(StatusCode.OK,
        fixture.table.tryAcquire(1, 1, 6, xShared, xOwner));
    assertEquals(StatusCode.OK,
        fixture.table.tryAcquire(2, 1, 2, yShared, yOwner));
    assertEquals(StatusCode.OK, fixture.table.tryAcquire(
        3, 1, 3,
        new LockRequest().setRange(13, 5, 13, 8, LockMode.UPDATE, 0), zOwner));
    LockExecutionLane yLane = new LockExecutionLane();
    LockWaitHandle yConversion = new LockWaitHandle();
    assertEquals(StatusCode.RETRY, fixture.table.enqueue(
        2, 1, 2, 1, 1, yUpdate, yLane, yConversion));

    LockToken aOwner = new LockToken();
    assertEquals(StatusCode.OK,
        fixture.table.tryAcquire(4, 1, 4, key(60, LockMode.EXCLUSIVE), aOwner));
    LockExecutionLane aLane = new LockExecutionLane();
    LockWaitHandle aWait = new LockWaitHandle();
    assertEquals(StatusCode.RETRY, fixture.table.enqueue(
        4, 1, 4, 1, 1,
        new LockRequest().setKey(13, 4, LockMode.UPDATE, 0), aLane, aWait));
    LockExecutionLane xLane = new LockExecutionLane();
    LockWaitHandle xWait = new LockWaitHandle();
    assertEquals(StatusCode.RETRY, fixture.table.enqueue(
        1, 1, 6, 1, 1, key(60, LockMode.EXCLUSIVE), xLane, xWait));
    assertEquals(0, fixture.table.deadlockVictimSelections());

    assertEquals(StatusCode.DEADLOCK, fixture.table.upgrade(xOwner, LockMode.UPDATE));
    assertEquals(1, fixture.table.deadlockVictimSelections());
    assertEquals(LockWaitState.QUEUED, aWait.state());
    assertEquals(LockWaitState.DEADLOCK, xWait.state());
    assertEquals(StatusCode.NOT_OWNER, fixture.table.acknowledge(xOwner));
    assertEquals(-1, fixture.table.state.directory.resource(xShared));
  }

  @Test
  void compatibleIntervalPrefixEnumeratesOverlapsOnce() {
    Fixture fixture = new Fixture();
    LockRequest wide = new LockRequest().setRange(
        14, 0, 14, 1_000, LockMode.EXCLUSIVE, 0);
    LockRequest cohort = new LockRequest().setRange(
        14, 100, 14, 900, LockMode.SHARED, 0);
    LockToken owner = new LockToken();
    assertEquals(StatusCode.OK, fixture.table.tryAcquire(1, 1, 1, wide, owner));
    LockWaitHandle[] cohortWaits = new LockWaitHandle[300];
    for (int index = 0; index < cohortWaits.length; index++) {
      cohortWaits[index] = new LockWaitHandle();
      assertEquals(StatusCode.RETRY, fixture.table.enqueue(
          2, 1, 2, index, 1, cohort,
          new LockExecutionLane(), cohortWaits[index]));
    }
    LockWaitHandle[] pointWaits = new LockWaitHandle[64];
    for (int index = 0; index < pointWaits.length; index++) {
      pointWaits[index] = new LockWaitHandle();
      assertEquals(StatusCode.RETRY, fixture.table.enqueue(
          10 + index, 1, 10 + index, 1, 1,
          new LockRequest().setKey(14, 200 + index, LockMode.SHARED, 0),
          new LockExecutionLane(), pointWaits[index]));
    }
    long searches = fixture.table.overlapSearches();
    long wakes = fixture.table.targetedWakes();
    long retained = fixture.arena.accountedBytes();

    assertEquals(StatusCode.OK, fixture.table.release(owner));
    assertTrue(fixture.table.overlapSearches() - searches <= pointWaits.length + 2);
    assertEquals(cohortWaits.length + pointWaits.length,
        fixture.table.targetedWakes() - wakes);
    for (LockWaitHandle wait : cohortWaits) {
      assertEquals(LockWaitState.GRANTED, wait.state());
    }
    for (LockWaitHandle wait : pointWaits) {
      assertEquals(LockWaitState.GRANTED, wait.state());
    }
    assertEquals(retained, fixture.arena.accountedBytes());

    fixture.table.lifecycle.releaseAll(2, 1, StatusCode.CANCELLED);
    for (int index = 0; index < pointWaits.length; index++) {
      fixture.table.lifecycle.releaseAll(10 + index, 1, StatusCode.CANCELLED);
    }
    assertEquals(0, fixture.table.waitingCount());
    assertEquals(0, fixture.table.holdingCount());
  }

  @Test
  void tupleKeyOwnerConvertsAheadOfAnOrdinaryTupleWaiter() {
    Fixture fixture = new Fixture();
    ByteBuffer key = ByteBuffer.allocate(Long.BYTES).putLong(0, 53);
    LockRequest sharedRequest = new LockRequest().setTupleKey(
        71, key, 0, Long.BYTES, LockMode.SHARED, 0);
    LockRequest exclusiveRequest = new LockRequest().setTupleKey(
        71, key, 0, Long.BYTES, LockMode.EXCLUSIVE, 0);
    LockToken shared = new LockToken();
    assertEquals(StatusCode.OK,
        fixture.table.tryAcquire(1, 1, 1, sharedRequest, shared));
    LockExecutionLane lane = new LockExecutionLane();
    LockWaitHandle waiter = new LockWaitHandle();
    assertEquals(StatusCode.RETRY,
        fixture.table.enqueue(2, 1, 2, 1, 1, exclusiveRequest, lane, waiter));

    LockToken converted = new LockToken();
    assertEquals(StatusCode.OK,
        fixture.table.tryAcquire(1, 1, 1, exclusiveRequest, converted));
    assertEquals(StatusCode.OK, fixture.table.release(shared));
    assertEquals(StatusCode.OK, fixture.table.release(converted));
    assertEquals(LockWaitState.GRANTED, waiter.state());
  }

  @Test
  void overlappingConversionBlocksNewReadersAndPrecedesTheirQueue() {
    Fixture fixture = new Fixture();
    LockRequest rangeShared = new LockRequest().setRange(
        11, 0, 11, 10, LockMode.SHARED, 0);
    LockRequest rangeExclusive = new LockRequest().setRange(
        11, 0, 11, 10, LockMode.EXCLUSIVE, 0);
    LockToken rangeOwner = new LockToken();
    LockToken overlappingOwner = new LockToken();
    assertEquals(StatusCode.OK,
        fixture.table.tryAcquire(1, 1, 1, rangeShared, rangeOwner));
    assertEquals(StatusCode.OK,
        fixture.table.tryAcquire(2, 1, 2, key(5, LockMode.SHARED), overlappingOwner));
    LockExecutionLane conversionLane = new LockExecutionLane();
    LockWaitHandle conversion = new LockWaitHandle();
    assertEquals(StatusCode.RETRY, fixture.table.enqueue(
        1, 1, 1, 1, 1, rangeExclusive, conversionLane, conversion));

    assertEquals(StatusCode.RETRY,
        fixture.table.tryAcquire(3, 1, 3, key(5, LockMode.SHARED), new LockToken()));
    LockExecutionLane readerLane = new LockExecutionLane();
    LockWaitHandle reader = new LockWaitHandle();
    assertEquals(StatusCode.RETRY, fixture.table.enqueue(
        3, 1, 3, 1, 1, key(5, LockMode.SHARED), readerLane, reader));
    assertEquals(StatusCode.OK, fixture.table.release(overlappingOwner));
    assertEquals(LockWaitState.GRANTED, conversion.state());
    assertEquals(LockWaitState.QUEUED, reader.state());
  }

  @Test
  void exactZeroDeadlineRetainsFinitePolicyInRequestArena() {
    Fixture fixture = new Fixture();
    LockToken owner = new LockToken();
    assertEquals(StatusCode.OK,
        fixture.table.tryAcquire(1, 1, 1, key(54, LockMode.EXCLUSIVE), owner));
    LockRequest finiteZero = key(54, LockMode.EXCLUSIVE).waitUntil(0);
    LockExecutionLane lane = new LockExecutionLane();
    LockWaitHandle handle = new LockWaitHandle();
    assertEquals(StatusCode.RETRY,
        fixture.table.enqueue(2, 1, 2, 1, 1, finiteZero, lane, handle));

    assertEquals(10, fixture.table.remainingNanos(lane, handle, -10));
    assertEquals(StatusCode.CANCELLED,
        fixture.table.cancel(lane, handle, StatusCode.CANCELLED));
  }

  @Test
  void maximumFiniteRemainingDurationDiffersFromAnInfiniteWait() {
    Fixture fixture = new Fixture();
    LockToken firstOwner = new LockToken();
    LockToken secondOwner = new LockToken();
    assertEquals(StatusCode.OK,
        fixture.table.tryAcquire(1, 1, 1, key(56, LockMode.EXCLUSIVE), firstOwner));
    assertEquals(StatusCode.OK,
        fixture.table.tryAcquire(3, 1, 3, key(57, LockMode.EXCLUSIVE), secondOwner));
    LockExecutionLane finiteLane = new LockExecutionLane();
    LockWaitHandle finiteHandle = new LockWaitHandle();
    assertEquals(StatusCode.RETRY, fixture.table.enqueue(
        2, 1, 2, 1, 1, key(56, LockMode.EXCLUSIVE).waitUntil(Long.MAX_VALUE),
        finiteLane, finiteHandle));
    LockExecutionLane infiniteLane = new LockExecutionLane();
    LockWaitHandle infiniteHandle = new LockWaitHandle();
    assertEquals(StatusCode.RETRY, fixture.table.enqueue(
        2, 1, 2, 2, 1, key(57, LockMode.EXCLUSIVE), infiniteLane, infiniteHandle));

    assertEquals(Long.MAX_VALUE,
        fixture.table.remainingNanos(finiteLane, finiteHandle, 0));
    assertEquals(-1, fixture.table.remainingNanos(infiniteLane, infiniteHandle, 0));
  }

  @Test
  void retryProbeLeavesNoRequestOrRetainedGrowth() {
    Fixture fixture = new Fixture();
    LockToken owner = new LockToken();
    assertEquals(StatusCode.OK, fixture.table.tryAcquire(1, 1, 1, key(1, LockMode.EXCLUSIVE), owner));
    long bytes = fixture.arena.accountedBytes();
    assertEquals(StatusCode.RETRY,
        fixture.table.tryAcquire(2, 1, 2, key(1, LockMode.EXCLUSIVE), new LockToken()));
    assertEquals(bytes, fixture.arena.accountedBytes());
    assertEquals(0, fixture.table.waitingCount());
  }

  @Test
  void fifoWriterGrantIsTargetedAndConsumable() {
    Fixture fixture = new Fixture();
    LockToken owner = new LockToken();
    assertEquals(StatusCode.OK, fixture.table.tryAcquire(1, 1, 1, key(2, LockMode.EXCLUSIVE), owner));
    LockExecutionLane firstLane = new LockExecutionLane();
    LockWaitHandle first = new LockWaitHandle();
    LockExecutionLane secondLane = new LockExecutionLane();
    LockWaitHandle second = new LockWaitHandle();
    assertEquals(StatusCode.RETRY,
        fixture.table.enqueue(2, 1, 2, 1, 1, key(2, LockMode.EXCLUSIVE), firstLane, first));
    assertEquals(StatusCode.RETRY,
        fixture.table.enqueue(3, 1, 3, 1, 1, key(2, LockMode.EXCLUSIVE), secondLane, second));
    assertEquals(StatusCode.OK, fixture.table.release(owner));
    assertEquals(LockWaitState.GRANTED, first.state());
    assertEquals(LockWaitState.QUEUED, second.state());
    assertEquals(1, fixture.table.targetedWakes());
    LockToken granted = new LockToken();
    assertEquals(StatusCode.OK, fixture.table.consume(firstLane, first, granted));
    assertEquals(StatusCode.OK, first.reset());
    assertEquals(StatusCode.OK, firstLane.reset());
    assertEquals(StatusCode.OK, fixture.table.release(granted));
    assertEquals(LockWaitState.GRANTED, second.state());
  }

  @Test
  void frozenHeadCannotGrantOrBeBypassedBeforeTerminalCleanup() {
    Fixture fixture = new Fixture();
    LockToken owner = new LockToken();
    assertEquals(StatusCode.OK,
        fixture.table.tryAcquire(1, 1, 1, key(22, LockMode.EXCLUSIVE), owner));
    LockExecutionLane frozenLane = new LockExecutionLane();
    LockWaitHandle frozen = new LockWaitHandle();
    LockExecutionLane survivorLane = new LockExecutionLane();
    LockWaitHandle survivor = new LockWaitHandle();
    assertEquals(StatusCode.RETRY, fixture.table.enqueue(
        2, 1, 2, 1, 1, key(22, LockMode.EXCLUSIVE), frozenLane, frozen));
    assertEquals(StatusCode.RETRY, fixture.table.enqueue(
        3, 1, 3, 1, 1, key(22, LockMode.EXCLUSIVE), survivorLane, survivor));

    fixture.table.lifecycle.freeze(2, 1);
    assertEquals(StatusCode.OK, fixture.table.release(owner));
    assertEquals(LockWaitState.QUEUED, frozen.state());
    assertEquals(LockWaitState.QUEUED, survivor.state());
    assertEquals(0, fixture.table.targetedWakes());

    fixture.table.lifecycle.releaseAll(2, 1, StatusCode.CONFLICT);
    assertEquals(LockWaitState.FAILED, frozen.state());
    assertEquals(StatusCode.CONFLICT,
        fixture.table.acknowledge(frozenLane, frozen));
    assertFalse(frozenLane.isPending());
    assertEquals(StatusCode.OK, frozenLane.reset());
    assertEquals(StatusCode.OK, frozen.reset());
    assertEquals(LockWaitState.GRANTED, survivor.state());
    assertEquals(1, fixture.table.targetedWakes());
  }

  @Test
  void releaseGrantsAllCompatibleSameTransactionLaneHeads() {
    Fixture fixture = new Fixture();
    LockToken owner = new LockToken();
    assertEquals(StatusCode.OK,
        fixture.table.tryAcquire(1, 1, 1, key(20, LockMode.EXCLUSIVE), owner));
    LockExecutionLane firstLane = new LockExecutionLane();
    LockWaitHandle first = new LockWaitHandle();
    LockExecutionLane secondLane = new LockExecutionLane();
    LockWaitHandle second = new LockWaitHandle();
    assertEquals(StatusCode.RETRY,
        fixture.table.enqueue(2, 1, 2, 1, 1, key(20, LockMode.EXCLUSIVE), firstLane, first));
    assertEquals(StatusCode.RETRY,
        fixture.table.enqueue(2, 1, 2, 2, 1, key(20, LockMode.EXCLUSIVE), secondLane, second));
    assertEquals(StatusCode.OK, fixture.table.release(owner));
    assertEquals(LockWaitState.GRANTED, first.state());
    assertEquals(LockWaitState.GRANTED, second.state());
    assertEquals(2, fixture.table.targetedWakes());
    LockToken firstToken = new LockToken();
    LockToken secondToken = new LockToken();
    assertEquals(StatusCode.OK, fixture.table.consume(firstLane, first, firstToken));
    assertEquals(StatusCode.OK, fixture.table.consume(secondLane, second, secondToken));
    assertEquals(firstToken.slot(), secondToken.slot());
    assertEquals(StatusCode.OK, fixture.table.release(firstToken));
    assertEquals(StatusCode.OK, fixture.table.release(secondToken));
  }

  @Test
  void releaseGrantsSharedThenCompatibleUpdateHead() {
    Fixture fixture = new Fixture();
    LockToken owner = new LockToken();
    assertEquals(StatusCode.OK,
        fixture.table.tryAcquire(1, 1, 1, key(21, LockMode.EXCLUSIVE), owner));
    LockExecutionLane sharedLane = new LockExecutionLane();
    LockWaitHandle shared = new LockWaitHandle();
    LockExecutionLane updateLane = new LockExecutionLane();
    LockWaitHandle update = new LockWaitHandle();
    assertEquals(StatusCode.RETRY,
        fixture.table.enqueue(2, 1, 2, 1, 1, key(21, LockMode.SHARED), sharedLane, shared));
    assertEquals(StatusCode.RETRY,
        fixture.table.enqueue(3, 1, 3, 1, 1, key(21, LockMode.UPDATE), updateLane, update));
    assertEquals(StatusCode.OK, fixture.table.release(owner));
    assertEquals(LockWaitState.GRANTED, shared.state());
    assertEquals(LockWaitState.GRANTED, update.state());
    LockToken sharedToken = new LockToken();
    LockToken updateToken = new LockToken();
    assertEquals(StatusCode.OK, fixture.table.consume(sharedLane, shared, sharedToken));
    assertEquals(StatusCode.OK, fixture.table.consume(updateLane, update, updateToken));
    assertEquals(StatusCode.OK, fixture.table.release(sharedToken));
    assertEquals(StatusCode.OK, fixture.table.release(updateToken));
  }

  @Test
  void compatibleSharedPrefixDoesNotBargeQueuedWriter() {
    Fixture fixture = new Fixture();
    LockToken owner = new LockToken();
    assertEquals(StatusCode.OK, fixture.table.tryAcquire(1, 1, 1, key(3, LockMode.EXCLUSIVE), owner));
    LockExecutionLane sharedLane = new LockExecutionLane();
    LockWaitHandle shared = new LockWaitHandle();
    LockExecutionLane writerLane = new LockExecutionLane();
    LockWaitHandle writer = new LockWaitHandle();
    LockExecutionLane lateReaderLane = new LockExecutionLane();
    LockWaitHandle lateReader = new LockWaitHandle();
    assertEquals(StatusCode.RETRY,
        fixture.table.enqueue(2, 1, 2, 1, 1, key(3, LockMode.SHARED), sharedLane, shared));
    assertEquals(StatusCode.RETRY,
        fixture.table.enqueue(3, 1, 3, 1, 1, key(3, LockMode.EXCLUSIVE), writerLane, writer));
    assertEquals(StatusCode.RETRY,
        fixture.table.enqueue(4, 1, 4, 1, 1, key(3, LockMode.SHARED), lateReaderLane, lateReader));
    assertEquals(StatusCode.OK, fixture.table.release(owner));
    assertEquals(LockWaitState.GRANTED, shared.state());
    assertEquals(LockWaitState.QUEUED, writer.state());
    assertEquals(LockWaitState.QUEUED, lateReader.state());
  }

  @Test
  void sharedOwnersPermitOneUpdateButBlockASecondUpdate() {
    Fixture fixture = new Fixture();
    LockToken first = new LockToken();
    LockToken second = new LockToken();
    assertEquals(StatusCode.OK,
        fixture.table.tryAcquire(1, 1, 1, key(30, LockMode.SHARED), first));
    assertEquals(StatusCode.OK,
        fixture.table.tryAcquire(2, 1, 2, key(30, LockMode.SHARED), second));
    assertEquals(StatusCode.OK, fixture.table.upgrade(first, LockMode.UPDATE));
    assertEquals(StatusCode.RETRY, fixture.table.upgrade(second, LockMode.UPDATE));
  }

  @Test
  void updateToExclusiveBlocksACompetingSharedOwner() {
    Fixture fixture = new Fixture();
    LockToken update = new LockToken();
    assertEquals(StatusCode.OK,
        fixture.table.tryAcquire(1, 1, 1, key(31, LockMode.UPDATE), update));
    assertEquals(StatusCode.OK, fixture.table.upgrade(update, LockMode.EXCLUSIVE));
    assertEquals(StatusCode.RETRY,
        fixture.table.tryAcquire(2, 1, 2, key(31, LockMode.SHARED), new LockToken()));
  }

  @Test
  void sameTransactionTokensReferenceOneHoldingAndReleaseAllInvalidatesStale() {
    Fixture fixture = new Fixture();
    LockToken first = new LockToken();
    LockToken second = new LockToken();
    assertEquals(StatusCode.OK, fixture.table.tryAcquire(7, 9, 7, key(4, LockMode.SHARED), first));
    assertEquals(StatusCode.OK, fixture.table.tryAcquire(7, 9, 7, key(4, LockMode.SHARED), second));
    assertEquals(first.slot(), second.slot());
    assertEquals(1, fixture.table.holdingCount());
    assertEquals(StatusCode.OK, fixture.table.release(first));
    assertEquals(1, fixture.table.holdingCount());
    assertEquals(StatusCode.NOT_OWNER, fixture.table.release(first));
    fixture.table.lifecycle.releaseAll(7, 9, StatusCode.CANCELLED);
    assertEquals(0, fixture.table.holdingCount());
    assertEquals(StatusCode.NOT_OWNER, fixture.table.release(second));
    assertEquals(StatusCode.OK, second.reset());
    LockToken reused = new LockToken();
    assertEquals(StatusCode.OK, fixture.table.tryAcquire(8, 1, 8, key(5, LockMode.EXCLUSIVE), reused));
    assertEquals(StatusCode.NOT_OWNER, fixture.table.release(second));
  }

  @Test
  void retainedOwnershipCoalescesReferencesAndSupportsConstantTimeLookup() {
    Fixture fixture = new Fixture();
    LockRequest shared = key(14, LockMode.SHARED);
    LockToken first = new LockToken();
    assertEquals(StatusCode.OK, fixture.table.tryAcquire(7, 1, 7, shared, first));
    assertEquals(StatusCode.OK, fixture.table.retain(first));
    assertFalse(first.isActive());
    assertEquals(StatusCode.OK, fixture.table.holds(7, 1, shared));
    assertEquals(StatusCode.NOT_OWNER,
        fixture.table.holds(7, 1, key(14, LockMode.EXCLUSIVE)));

    LockToken upgrade = new LockToken();
    assertEquals(StatusCode.OK, fixture.table.tryAcquire(
        7, 1, 7, key(14, LockMode.EXCLUSIVE), upgrade));
    assertEquals(StatusCode.OK, fixture.table.retain(upgrade));
    assertEquals(1, fixture.table.holdingCount());
    assertEquals(StatusCode.OK,
        fixture.table.holds(7, 1, key(14, LockMode.EXCLUSIVE)));
    fixture.table.lifecycle.releaseAll(7, 1, StatusCode.CANCELLED);
    assertEquals(0, fixture.table.holdingCount());
  }

  @Test
  void terminalCleanupDetachesRequestsAndLaneAcknowledgesWithoutLiveSlot() {
    Fixture fixture = new Fixture();
    LockToken owner = new LockToken();
    assertEquals(StatusCode.OK, fixture.table.tryAcquire(1, 1, 1, key(6, LockMode.EXCLUSIVE), owner));
    LockExecutionLane lane = new LockExecutionLane();
    LockWaitHandle handle = new LockWaitHandle();
    assertEquals(StatusCode.RETRY,
        fixture.table.enqueue(2, 3, 2, 99, 4, key(6, LockMode.EXCLUSIVE), lane, handle));
    fixture.table.lifecycle.releaseAll(2, 3, StatusCode.CANCELLED);
    assertEquals(LockWaitState.CANCELLED, handle.state());
    assertTrue(lane.isPending());
    assertEquals(StatusCode.CONFLICT, handle.reset());
    assertEquals(StatusCode.CANCELLED, fixture.table.acknowledge(lane, handle));
    assertFalse(lane.isPending());
    assertEquals(StatusCode.OK, handle.reset());
  }

  @Test
  void creatorCancellationLeavesCanonicalReservedHoldingForFollower() {
    Fixture fixture = new Fixture();
    LockToken owner = new LockToken();
    assertEquals(StatusCode.OK, fixture.table.tryAcquire(1, 1, 1, key(7, LockMode.EXCLUSIVE), owner));
    LockExecutionLane creatorLane = new LockExecutionLane();
    LockWaitHandle creator = new LockWaitHandle();
    LockExecutionLane followerLane = new LockExecutionLane();
    LockWaitHandle follower = new LockWaitHandle();
    assertEquals(StatusCode.RETRY,
        fixture.table.enqueue(2, 1, 2, 1, 1, key(7, LockMode.EXCLUSIVE), creatorLane, creator));
    assertEquals(StatusCode.RETRY,
        fixture.table.enqueue(2, 1, 2, 2, 1, key(7, LockMode.EXCLUSIVE), followerLane, follower));
    assertEquals(StatusCode.CANCELLED,
        fixture.table.cancel(creatorLane, creator, StatusCode.CANCELLED));
    assertEquals(StatusCode.RETRY,
        fixture.table.tryAcquire(2, 1, 2, key(7, LockMode.EXCLUSIVE), new LockToken()));
    assertEquals(StatusCode.OK, fixture.table.release(owner));
    assertEquals(LockWaitState.GRANTED, follower.state());
    LockToken granted = new LockToken();
    assertEquals(StatusCode.OK, fixture.table.consume(followerLane, follower, granted));
    assertEquals(StatusCode.OK, fixture.table.release(granted));
  }

  @Test
  void duplicateLiveLaneIdentityRejectsFreshCarriersWithoutGrowth() {
    Fixture fixture = new Fixture();
    LockToken owner = new LockToken();
    assertEquals(StatusCode.OK,
        fixture.table.tryAcquire(1, 1, 1, key(40, LockMode.EXCLUSIVE), owner));
    LockExecutionLane admittedLane = new LockExecutionLane();
    LockWaitHandle admittedHandle = new LockWaitHandle();
    assertEquals(StatusCode.RETRY, fixture.table.enqueue(
        2, 1, 2, 77, 3, key(40, LockMode.EXCLUSIVE), admittedLane, admittedHandle));
    long bytes = fixture.arena.accountedBytes();
    assertEquals(StatusCode.CONFLICT, fixture.table.enqueue(
        2, 1, 2, 77, 3, key(40, LockMode.EXCLUSIVE),
        new LockExecutionLane(), new LockWaitHandle()));
    assertEquals(bytes, fixture.arena.accountedBytes());
    assertEquals(1, fixture.table.waitingCount());
    assertEquals(StatusCode.CONFLICT, fixture.table.enqueue(
        2, 1, 2, 78, 3, key(40, LockMode.EXCLUSIVE), admittedLane, new LockWaitHandle()));
    assertEquals(StatusCode.CONFLICT, fixture.table.enqueue(
        2, 1, 2, 79, 3, key(40, LockMode.EXCLUSIVE),
        new LockExecutionLane(), admittedHandle));
  }

  @Test
  void terminalAcknowledgementAllowsSameLaneIdWithHigherGeneration() {
    Fixture fixture = new Fixture();
    LockToken owner = new LockToken();
    assertEquals(StatusCode.OK,
        fixture.table.tryAcquire(1, 1, 1, key(41, LockMode.EXCLUSIVE), owner));
    LockExecutionLane lane = new LockExecutionLane();
    LockWaitHandle handle = new LockWaitHandle();
    assertEquals(StatusCode.RETRY, fixture.table.enqueue(
        2, 1, 2, 88, 4, key(41, LockMode.EXCLUSIVE), lane, handle));
    fixture.table.lifecycle.releaseAll(2, 1, StatusCode.CANCELLED);
    assertEquals(StatusCode.CANCELLED, fixture.table.acknowledge(lane, handle));
    assertEquals(StatusCode.OK, lane.reset());
    assertEquals(StatusCode.OK, handle.reset());
    assertEquals(StatusCode.RETRY, fixture.table.enqueue(
        2, 1, 2, 88, 5, key(41, LockMode.EXCLUSIVE), lane, handle));
  }

  @Test
  void laneIndexPreservesCollisionsAcrossIncrementalGrowth() {
    Fixture fixture = new Fixture();
    LockToken owner = new LockToken();
    assertEquals(StatusCode.OK,
        fixture.table.tryAcquire(1, 1, 1, key(42, LockMode.EXCLUSIVE), owner));
    long firstLane = 0;
    long firstHash = LockExactDirectory.laneHash(2, 1, firstLane, 1);
    long bucket = fixture.table.state.directory.laneIndex.bucketForTest(firstHash);
    long collidingLane = 1;
    while (fixture.table.state.directory.laneIndex.bucketForTest(
        LockExactDirectory.laneHash(2, 1, collidingLane, 1)) != bucket) collidingLane++;
    assertEquals(StatusCode.RETRY, fixture.table.enqueue(
        2, 1, 2, firstLane, 1, key(42, LockMode.EXCLUSIVE),
        new LockExecutionLane(), new LockWaitHandle()));
    assertEquals(StatusCode.RETRY, fixture.table.enqueue(
        2, 1, 2, collidingLane, 1, key(42, LockMode.EXCLUSIVE),
        new LockExecutionLane(), new LockWaitHandle()));
    for (long laneId = 100; laneId < 112; laneId++) {
      assertEquals(StatusCode.RETRY, fixture.table.enqueue(
          2, 1, 2, laneId, 1, key(42, LockMode.EXCLUSIVE),
          new LockExecutionLane(), new LockWaitHandle()));
    }
    long bytes = fixture.arena.accountedBytes();
    assertEquals(StatusCode.CONFLICT, fixture.table.enqueue(
        2, 1, 2, collidingLane, 1, key(42, LockMode.EXCLUSIVE),
        new LockExecutionLane(), new LockWaitHandle()));
    assertEquals(bytes, fixture.arena.accountedBytes());
    assertEquals(14, fixture.table.waitingCount());
  }

  @Test
  void malformedExactRequestsAreRejectedWithoutAdmission() {
    Fixture fixture = new Fixture();
    LockRequest nullScope = new LockRequest().setExact(null, 1, 2, LockMode.SHARED, 0);
    LockRequest nullMode = new LockRequest().setExact(LockScope.ROW, 1, 2, null, 0);
    LockRequest malformedKey = new LockRequest().setKey(-1, 4, LockMode.SHARED, 0);
    long bytes = fixture.arena.accountedBytes();
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        fixture.table.tryAcquire(1, 1, 1, nullScope, new LockToken()));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        fixture.table.tryAcquire(1, 1, 1, nullMode, new LockToken()));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        fixture.table.tryAcquire(1, 1, 1, malformedKey, new LockToken()));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, fixture.table.enqueue(
        1, 1, 1, 1, 1, malformedKey, new LockExecutionLane(), new LockWaitHandle()));
    assertEquals(bytes, fixture.arena.accountedBytes());
  }

  private static LockRequest key(long key, LockMode mode) {
    return new LockRequest().setKey(11, key, mode, 0);
  }

  private static void assertConversionRemovalDetectsCycle(
      LockRequest sharedOwner, LockRequest updateConversion,
      LockRequest compatibleWaiter, LockRequest exclusiveWaiter,
      StatusCode removalOutcome) {
    Fixture fixture = new Fixture();
    LockToken xOwner = new LockToken();
    LockToken yOwner = new LockToken();
    LockToken zOwner = new LockToken();
    assertEquals(StatusCode.OK,
        fixture.table.tryAcquire(1, 1, 1, sharedOwner, xOwner));
    assertEquals(StatusCode.OK,
        fixture.table.tryAcquire(2, 1, 2, sharedOwner, yOwner));
    assertEquals(StatusCode.OK,
        fixture.table.tryAcquire(3, 1, 3, updateConversion, zOwner));

    LockExecutionLane yLane = new LockExecutionLane();
    LockWaitHandle yConversion = new LockWaitHandle();
    assertEquals(StatusCode.RETRY, fixture.table.enqueue(
        2, 1, 2, 1, 1, updateConversion, yLane, yConversion));
    LockExecutionLane aLane = new LockExecutionLane();
    LockWaitHandle aWait = new LockWaitHandle();
    assertEquals(StatusCode.RETRY, fixture.table.enqueue(
        4, 1, 4, 1, 1, compatibleWaiter, aLane, aWait));
    LockExecutionLane bLane = new LockExecutionLane();
    LockWaitHandle bWait = new LockWaitHandle();
    assertEquals(StatusCode.RETRY, fixture.table.enqueue(
        5, 1, 5, 1, 1, exclusiveWaiter, bLane, bWait));

    LockToken bOwner = new LockToken();
    assertEquals(StatusCode.OK,
        fixture.table.tryAcquire(5, 1, 5, key(59, LockMode.EXCLUSIVE), bOwner));
    LockExecutionLane xLane = new LockExecutionLane();
    LockWaitHandle xWait = new LockWaitHandle();
    assertEquals(StatusCode.RETRY, fixture.table.enqueue(
        1, 1, 1, 1, 1, key(59, LockMode.EXCLUSIVE), xLane, xWait));
    assertEquals(0, fixture.table.deadlockVictimSelections());
    long retained = fixture.arena.accountedBytes();

    assertEquals(removalOutcome,
        fixture.table.cancel(yLane, yConversion, removalOutcome));
    assertEquals(LockWaitState.GRANTED, aWait.state());
    assertEquals(LockWaitState.DEADLOCK, bWait.state());
    assertEquals(LockWaitState.GRANTED, xWait.state());
    assertEquals(1, fixture.table.deadlockVictimSelections());
    assertEquals(retained, fixture.arena.accountedBytes());
  }

  private static final class Fixture {
    final LockSegmentArena arena = new LockSegmentArena(new LockMemoryEnvelope(32L << 20));
    final LockExactTable table = new LockExactTable(new Object(), 73, arena);
  }
}
