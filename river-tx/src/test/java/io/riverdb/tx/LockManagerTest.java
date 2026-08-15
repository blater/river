package io.riverdb.tx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.key.OrderedKey;
import io.riverdb.tx.api.lock.LockMode;
import io.riverdb.tx.api.lock.LockRequest;
import io.riverdb.tx.api.lock.LockScope;
import io.riverdb.tx.api.lock.LockToken;
import org.junit.jupiter.api.Test;

final class LockManagerTest {
  @Test
  void lockRequestCarriesExactOrderedEndpoints() {
    LockRequest request = new LockRequest().setKey(7, Long.MAX_VALUE, LockMode.UPDATE, 91);
    assertEquals(LockScope.KEY, request.scope());
    assertEquals(7, request.lowerSpace());
    assertEquals(Long.MAX_VALUE, request.lowerKey());
    assertEquals(7, request.upperSpace());
    assertEquals(Long.MAX_VALUE, request.upperKey());
    assertEquals(LockMode.UPDATE, request.mode());
    assertEquals(91, request.deadlineNanos());

    request.setRange(7, Long.MIN_VALUE, 8, Long.MIN_VALUE, LockMode.SHARED, 0);
    assertEquals(LockScope.RANGE, request.scope());
    assertEquals(7, request.lowerSpace());
    assertEquals(Long.MIN_VALUE, request.lowerKey());
    assertEquals(8, request.upperSpace());
    assertEquals(Long.MIN_VALUE, request.upperKey());

    LockManager locks = new LockManager(1);
    LockToken rejected = new LockToken();
    request.setExact(LockScope.KEY, 7, 7, LockMode.SHARED, 0);
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        locks.tryAcquire(
            1,
            request.scope(),
            request.lowerSpace(),
            request.lowerKey(),
            request.upperSpace(),
            request.upperKey(),
            request.mode(),
            0,
            0,
            rejected));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        locks.tryAcquire(
            1,
            LockScope.ROW,
            1,
            7,
            0,
            8,
            LockMode.SHARED,
            0,
            0,
            rejected));
    request.setExact(LockScope.RANGE, 7, 8, LockMode.SHARED, 0);
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        locks.tryAcquire(
            1,
            request.scope(),
            request.lowerSpace(),
            request.lowerKey(),
            request.upperSpace(),
            request.upperKey(),
            request.mode(),
            0,
            0,
            rejected));
  }

  @Test
  void exclusiveKeyLockContendsUntilOwnerReleases() {
    LockManager locks = new LockManager(2);
    LockToken first = new LockToken();
    LockToken second = new LockToken();
    assertEquals(
        StatusCode.OK,
        acquireKey(locks, 11, 1, 91, LockMode.EXCLUSIVE, 0, 0, first));
    assertEquals(
        StatusCode.RETRY,
        acquireKey(locks, 12, 1, 91, LockMode.EXCLUSIVE, 0, 0, second));
    assertEquals(
        StatusCode.TIMEOUT,
        acquireKey(locks, 12, 1, 91, LockMode.EXCLUSIVE, 10, 10, second));
    assertEquals(0, locks.waitingCount());
    assertEquals(1, locks.activeLockCount());
    assertEquals(StatusCode.OK, locks.release(first));
    assertFalse(first.isActive());
    assertEquals(StatusCode.NOT_OWNER, locks.release(first));
    assertEquals(
        StatusCode.OK,
        acquireKey(locks, 12, 1, 91, LockMode.EXCLUSIVE, 0, 0, second));
    assertTrue(second.isActive());
    assertEquals(StatusCode.OK, locks.release(second));
    assertEquals(0, locks.activeLockCount());
  }

  @Test
  void sharedLocksCoexistAndTokensCannotCrossProviders() {
    LockManager locks = new LockManager(2);
    LockManager other = new LockManager(2);
    LockToken first = new LockToken();
    LockToken second = new LockToken();
    assertEquals(
        StatusCode.OK,
        acquireExact(locks, 21, LockScope.ROW, 2, 4, LockMode.SHARED, first));
    assertEquals(
        StatusCode.OK,
        acquireExact(locks, 22, LockScope.ROW, 2, 4, LockMode.SHARED, second));
    assertEquals(StatusCode.NOT_OWNER, other.release(first));
    assertTrue(first.isActive());
    assertEquals(StatusCode.OK, locks.release(first));
    assertEquals(StatusCode.OK, locks.release(second));
  }

  @Test
  void upgradeWaitsForOtherReaderThenBecomesExclusive() {
    LockManager locks = new LockManager(3);
    LockToken first = new LockToken();
    LockToken second = new LockToken();
    LockToken writer = new LockToken();
    assertEquals(
        StatusCode.OK,
        acquireKey(locks, 31, 1, 7, LockMode.SHARED, 0, 0, first));
    assertEquals(
        StatusCode.OK,
        acquireKey(locks, 32, 1, 7, LockMode.SHARED, 0, 0, second));
    assertEquals(StatusCode.RETRY, locks.upgrade(first, LockMode.EXCLUSIVE, 0, 0));
    assertEquals(StatusCode.OK, locks.release(second));
    assertEquals(StatusCode.OK, locks.upgrade(first, LockMode.EXCLUSIVE, 0, 0));
    assertEquals(
        StatusCode.RETRY,
        acquireKey(locks, 33, 1, 7, LockMode.SHARED, 0, 0, writer));
    assertEquals(StatusCode.OK, locks.release(first));
  }

  @Test
  void grantsContendedLocksInWaitOrder() {
    LockManager locks = new LockManager(6);
    LockToken owner = new LockToken();
    LockToken firstWaiter = new LockToken();
    LockToken secondWaiter = new LockToken();
    assertEquals(
        StatusCode.OK,
        acquireKey(locks, 41, 1, 8, LockMode.EXCLUSIVE, 0, 0, owner));
    assertEquals(
        StatusCode.RETRY,
        acquireKey(locks, 42, 1, 8, LockMode.EXCLUSIVE, 0, 0, firstWaiter));
    assertEquals(
        StatusCode.RETRY,
        acquireKey(locks, 43, 1, 8, LockMode.EXCLUSIVE, 0, 0, secondWaiter));
    assertEquals(2, locks.waitingCount());
    assertEquals(StatusCode.OK, locks.release(owner));
    assertEquals(
        StatusCode.RETRY,
        acquireKey(locks, 43, 1, 8, LockMode.EXCLUSIVE, 0, 0, secondWaiter));
    assertEquals(
        StatusCode.OK,
        acquireKey(locks, 42, 1, 8, LockMode.EXCLUSIVE, 0, 0, firstWaiter));
    assertEquals(1, locks.waitingCount());
    assertEquals(StatusCode.OK, locks.release(firstWaiter));
    assertEquals(
        StatusCode.OK,
        acquireKey(locks, 43, 1, 8, LockMode.EXCLUSIVE, 0, 0, secondWaiter));
    assertEquals(0, locks.waitingCount());
    assertEquals(StatusCode.OK, locks.release(secondWaiter));
  }

  @Test
  void choosesHighestTransactionAsDeadlockVictim() {
    LockManager locks = new LockManager(6);
    LockToken firstA = new LockToken();
    LockToken secondB = new LockToken();
    LockToken firstB = new LockToken();
    LockToken secondA = new LockToken();
    assertEquals(
        StatusCode.OK,
        acquireKey(locks, 51, 1, 10, LockMode.EXCLUSIVE, 0, 0, firstA));
    assertEquals(
        StatusCode.OK,
        acquireKey(locks, 52, 1, 20, LockMode.EXCLUSIVE, 0, 0, secondB));
    assertEquals(
        StatusCode.RETRY,
        acquireKey(locks, 51, 1, 20, LockMode.EXCLUSIVE, 0, 0, firstB));
    assertEquals(
        StatusCode.CONFLICT,
        acquireKey(locks, 52, 1, 10, LockMode.EXCLUSIVE, 0, 0, secondA));
    assertEquals(true, locks.isDeadlockVictim(52));
    assertEquals(false, locks.isDeadlockVictim(51));
    assertEquals(1, locks.waitingCount());
    assertEquals(StatusCode.OK, locks.release(secondB));
    locks.transactionCompleted(52);
    assertEquals(
        StatusCode.OK,
        acquireKey(locks, 51, 1, 20, LockMode.EXCLUSIVE, 0, 0, firstB));
    assertEquals(0, locks.waitingCount());
    assertEquals(StatusCode.OK, locks.release(firstB));
    assertEquals(StatusCode.OK, locks.release(firstA));
  }

  @Test
  void notifiesPreviouslyWaitingVictimOnItsNextAttempt() {
    LockManager locks = new LockManager(6);
    LockToken olderB = new LockToken();
    LockToken youngerA = new LockToken();
    LockToken youngerB = new LockToken();
    LockToken olderA = new LockToken();
    assertEquals(
        StatusCode.OK,
        acquireKey(locks, 71, 1, 20, LockMode.EXCLUSIVE, 0, 0, olderB));
    assertEquals(
        StatusCode.OK,
        acquireKey(locks, 72, 1, 10, LockMode.EXCLUSIVE, 0, 0, youngerA));
    assertEquals(
        StatusCode.RETRY,
        acquireKey(locks, 72, 1, 20, LockMode.EXCLUSIVE, 0, 0, youngerB));
    assertEquals(
        StatusCode.RETRY,
        acquireKey(locks, 71, 1, 10, LockMode.EXCLUSIVE, 0, 0, olderA));
    assertEquals(true, locks.isDeadlockVictim(72));
    assertEquals(
        StatusCode.CONFLICT,
        acquireKey(locks, 72, 1, 20, LockMode.EXCLUSIVE, 0, 0, youngerB));
    assertEquals(StatusCode.OK, locks.release(youngerA));
    locks.transactionCompleted(72);
    assertEquals(
        StatusCode.OK,
        acquireKey(locks, 71, 1, 10, LockMode.EXCLUSIVE, 0, 0, olderA));
    assertEquals(StatusCode.OK, locks.release(olderA));
    assertEquals(StatusCode.OK, locks.release(olderB));
  }

  @Test
  void detectsConversionDeadlock() {
    LockManager locks = new LockManager(6);
    LockToken first = new LockToken();
    LockToken second = new LockToken();
    assertEquals(
        StatusCode.OK,
        acquireKey(locks, 61, 1, 30, LockMode.SHARED, 0, 0, first));
    assertEquals(
        StatusCode.OK,
        acquireKey(locks, 62, 1, 30, LockMode.SHARED, 0, 0, second));
    assertEquals(StatusCode.RETRY, locks.upgrade(first, LockMode.EXCLUSIVE, 0, 0));
    assertEquals(StatusCode.CONFLICT, locks.upgrade(second, LockMode.EXCLUSIVE, 0, 0));
    assertEquals(true, locks.isDeadlockVictim(62));
    assertEquals(StatusCode.OK, locks.release(second));
    locks.transactionCompleted(62);
    assertEquals(StatusCode.OK, locks.upgrade(first, LockMode.EXCLUSIVE, 0, 0));
    assertEquals(StatusCode.OK, locks.release(first));
  }

  @Test
  void sharedRangesBlockOnlyKeysInsideTheirHalfOpenBounds() {
    LockManager locks = new LockManager(8);
    LockToken firstRange = new LockToken();
    LockToken secondRange = new LockToken();
    LockToken below = new LockToken();
    LockToken above = new LockToken();
    LockToken inside = new LockToken();
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        locks.tryAcquire(
            80, LockScope.RANGE, 1, 20, 1, 20, LockMode.SHARED, 0, 0, firstRange));
    assertEquals(
        StatusCode.OK,
        locks.tryAcquire(
            81, LockScope.RANGE, 1, 10, 1, 20, LockMode.SHARED, 0, 0, firstRange));
    assertEquals(
        StatusCode.OK,
        acquireKey(locks, 82, 1, 9, LockMode.EXCLUSIVE, 0, 0, below));
    assertEquals(
        StatusCode.OK,
        acquireKey(locks, 83, 1, 20, LockMode.EXCLUSIVE, 0, 0, above));
    assertEquals(StatusCode.OK, locks.release(above));
    assertEquals(
        StatusCode.OK,
        locks.tryAcquire(
            84, LockScope.RANGE, 1, 15, 1, 25, LockMode.SHARED, 0, 0, secondRange));
    assertEquals(
        StatusCode.RETRY,
        acquireKey(locks, 85, 1, 15, LockMode.EXCLUSIVE, 0, 0, inside));
    assertEquals(StatusCode.OK, locks.release(firstRange));
    assertEquals(
        StatusCode.RETRY,
        acquireKey(locks, 85, 1, 15, LockMode.EXCLUSIVE, 0, 0, inside));
    assertEquals(StatusCode.OK, locks.release(secondRange));
    assertEquals(
        StatusCode.OK,
        acquireKey(locks, 85, 1, 15, LockMode.EXCLUSIVE, 0, 0, inside));
    assertEquals(StatusCode.OK, locks.release(inside));
    assertEquals(StatusCode.OK, locks.release(below));
  }

  @Test
  void detectsDeadlockAcrossRangeAndKeyResources() {
    LockManager locks = new LockManager(6);
    LockToken range = new LockToken();
    LockToken youngerKey = new LockToken();
    LockToken rangeOwnerWait = new LockToken();
    LockToken youngerWait = new LockToken();
    assertEquals(
        StatusCode.OK,
        locks.tryAcquire(
            91, LockScope.RANGE, 1, 10, 1, 20, LockMode.SHARED, 0, 0, range));
    assertEquals(
        StatusCode.OK,
        acquireKey(locks, 92, 1, 25, LockMode.EXCLUSIVE, 0, 0, youngerKey));
    assertEquals(
        StatusCode.RETRY,
        acquireKey(locks, 91, 1, 25, LockMode.EXCLUSIVE, 0, 0, rangeOwnerWait));
    assertEquals(
        StatusCode.CONFLICT,
        acquireKey(locks, 92, 1, 15, LockMode.EXCLUSIVE, 0, 0, youngerWait));
    assertTrue(locks.isDeadlockVictim(92));
    assertEquals(1, locks.deadlockVictimSelections());
    assertEquals(StatusCode.OK, locks.release(youngerKey));
    locks.transactionCompleted(92);
    assertEquals(
        StatusCode.OK,
        acquireKey(locks, 91, 1, 25, LockMode.EXCLUSIVE, 0, 0, rangeOwnerWait));
    assertEquals(StatusCode.OK, locks.release(rangeOwnerWait));
    assertEquals(StatusCode.OK, locks.release(range));
  }

  @Test
  void transactionCanUpgradeItsKeyInsideItsOwnRange() {
    LockManager locks = new LockManager(4);
    LockToken key = new LockToken();
    LockToken range = new LockToken();
    assertEquals(
        StatusCode.OK,
        acquireKey(locks, 101, 1, 15, LockMode.SHARED, 0, 0, key));
    assertEquals(
        StatusCode.OK,
        locks.tryAcquire(
            101, LockScope.RANGE, 1, 10, 1, 20, LockMode.SHARED, 0, 0, range));
    assertEquals(StatusCode.OK, locks.upgrade(key, LockMode.EXCLUSIVE, 0, 0));
    assertEquals(StatusCode.OK, locks.release(key));
    assertEquals(StatusCode.OK, locks.release(range));
  }

  @Test
  void exactKeySpacesKeepSignedExtremaIndependent() {
    LockManager locks = new LockManager(5);
    LockToken maximum = new LockToken();
    LockToken sameMaximum = new LockToken();
    LockToken otherSpace = new LockToken();
    LockToken minimum = new LockToken();
    assertEquals(
        StatusCode.OK,
        locks.tryAcquire(
            111,
            LockScope.KEY,
            7,
            Long.MAX_VALUE,
            7,
            Long.MAX_VALUE,
            LockMode.EXCLUSIVE,
            0,
            0,
            maximum));
    assertEquals(
        StatusCode.RETRY,
        locks.tryAcquire(
            112,
            LockScope.KEY,
            7,
            Long.MAX_VALUE,
            7,
            Long.MAX_VALUE,
            LockMode.EXCLUSIVE,
            0,
            0,
            sameMaximum));
    assertEquals(
        StatusCode.OK,
        locks.tryAcquire(
            113,
            LockScope.KEY,
            8,
            Long.MAX_VALUE,
            8,
            Long.MAX_VALUE,
            LockMode.EXCLUSIVE,
            0,
            0,
            otherSpace));
    assertEquals(
        StatusCode.OK,
        locks.tryAcquire(
            114,
            LockScope.KEY,
            7,
            Long.MIN_VALUE,
            7,
            Long.MIN_VALUE,
            LockMode.EXCLUSIVE,
            0,
            0,
            minimum));
    assertEquals(StatusCode.OK, locks.release(minimum));
    assertEquals(StatusCode.OK, locks.release(otherSpace));
    assertEquals(StatusCode.OK, locks.release(maximum));
    locks.cancelWait(112);
  }

  @Test
  void fullSpaceRangeUsesNextSpaceEndpointAndCanonicalInfinity() {
    LockManager locks = new LockManager(8);
    LockToken fullSpace = new LockToken();
    LockToken lowerEdge = new LockToken();
    LockToken upperEdge = new LockToken();
    LockToken canonicalInfinity = new LockToken();
    LockToken rejected = new LockToken();
    assertEquals(
        StatusCode.OK,
        locks.tryAcquire(
            121,
            LockScope.RANGE,
            7,
            Long.MIN_VALUE,
            8,
            Long.MIN_VALUE,
            LockMode.SHARED,
            0,
            0,
            fullSpace));
    assertEquals(
        StatusCode.RETRY,
        locks.tryAcquire(
            122,
            LockScope.KEY,
            7,
            Long.MIN_VALUE,
            7,
            Long.MIN_VALUE,
            LockMode.EXCLUSIVE,
            0,
            0,
            lowerEdge));
    assertEquals(
        StatusCode.OK,
        locks.tryAcquire(
            123,
            LockScope.KEY,
            8,
            Long.MIN_VALUE,
            8,
            Long.MIN_VALUE,
            LockMode.EXCLUSIVE,
            0,
            0,
            upperEdge));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        locks.tryAcquire(
            124,
            LockScope.RANGE,
            9,
            0,
            OrderedKey.INFINITY_SPACE,
            Long.MIN_VALUE,
            LockMode.SHARED,
            0,
            0,
            rejected));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        locks.tryAcquire(
            124,
            LockScope.RANGE,
            9,
            0,
            OrderedKey.INFINITY_SPACE,
            Long.MAX_VALUE,
            LockMode.SHARED,
            0,
            0,
            rejected));
    assertEquals(
        StatusCode.OK,
        locks.tryAcquire(
            124,
            LockScope.RANGE,
            9,
            0,
            OrderedKey.INFINITY_SPACE,
            0,
            LockMode.SHARED,
            0,
            0,
            canonicalInfinity));
    assertEquals(StatusCode.OK, locks.release(canonicalInfinity));
    assertEquals(StatusCode.OK, locks.release(upperEdge));
    assertEquals(StatusCode.OK, locks.release(fullSpace));
    locks.cancelWait(122);
  }

  @Test
  void exclusiveRangesDistinguishOverlapFromAdjacentEndpoints() {
    LockManager locks = new LockManager(4);
    LockToken owner = new LockToken();
    LockToken overlapping = new LockToken();
    LockToken adjacent = new LockToken();
    assertEquals(
        StatusCode.OK,
        locks.tryAcquire(
            131,
            LockScope.RANGE,
            5,
            10,
            6,
            20,
            LockMode.EXCLUSIVE,
            0,
            0,
            owner));
    assertEquals(
        StatusCode.OK,
        locks.tryAcquire(
            133,
            LockScope.RANGE,
            6,
            20,
            7,
            0,
            LockMode.EXCLUSIVE,
            0,
            0,
            adjacent));
    assertEquals(
        StatusCode.RETRY,
        locks.tryAcquire(
            132,
            LockScope.RANGE,
            6,
            19,
            7,
            0,
            LockMode.EXCLUSIVE,
            0,
            0,
            overlapping));
    assertEquals(StatusCode.OK, locks.release(adjacent));
    assertEquals(StatusCode.OK, locks.release(owner));
    locks.cancelWait(132);
  }

  private static StatusCode acquireKey(
      LockManager locks,
      long transactionId,
      int space,
      long key,
      LockMode mode,
      long deadlineNanos,
      long nowNanos,
      LockToken token) {
    return locks.tryAcquire(
        transactionId,
        LockScope.KEY,
        space,
        key,
        space,
        key,
        mode,
        deadlineNanos,
        nowNanos,
        token);
  }

  private static StatusCode acquireExact(
      LockManager locks,
      long transactionId,
      LockScope scope,
      long identityHigh,
      long identityLow,
      LockMode mode,
      LockToken token) {
    return locks.tryAcquire(
        transactionId,
        scope,
        0,
        identityHigh,
        0,
        identityLow,
        mode,
        0,
        0,
        token);
  }
}
