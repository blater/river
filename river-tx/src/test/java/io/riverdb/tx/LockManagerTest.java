package io.riverdb.tx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.tx.api.lock.LockMode;
import io.riverdb.tx.api.lock.LockScope;
import io.riverdb.tx.api.lock.LockToken;
import org.junit.jupiter.api.Test;

final class LockManagerTest {
  @Test
  void exclusiveKeyLockContendsUntilOwnerReleases() {
    LockManager locks = new LockManager(2);
    LockToken first = new LockToken();
    LockToken second = new LockToken();
    assertEquals(
        StatusCode.OK,
        locks.tryAcquire(11, LockScope.KEY, 1, 91, LockMode.EXCLUSIVE, 0, 0, first));
    assertEquals(
        StatusCode.RETRY,
        locks.tryAcquire(12, LockScope.KEY, 1, 91, LockMode.EXCLUSIVE, 0, 0, second));
    assertEquals(
        StatusCode.TIMEOUT,
        locks.tryAcquire(12, LockScope.KEY, 1, 91, LockMode.EXCLUSIVE, 10, 10, second));
    assertEquals(0, locks.waitingCount());
    assertEquals(1, locks.activeLockCount());
    assertEquals(StatusCode.OK, locks.release(first));
    assertFalse(first.isActive());
    assertEquals(StatusCode.NOT_OWNER, locks.release(first));
    assertEquals(
        StatusCode.OK,
        locks.tryAcquire(12, LockScope.KEY, 1, 91, LockMode.EXCLUSIVE, 0, 0, second));
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
        locks.tryAcquire(21, LockScope.ROW, 2, 4, LockMode.SHARED, 0, 0, first));
    assertEquals(
        StatusCode.OK,
        locks.tryAcquire(22, LockScope.ROW, 2, 4, LockMode.SHARED, 0, 0, second));
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
        locks.tryAcquire(31, LockScope.KEY, 1, 7, LockMode.SHARED, 0, 0, first));
    assertEquals(
        StatusCode.OK,
        locks.tryAcquire(32, LockScope.KEY, 1, 7, LockMode.SHARED, 0, 0, second));
    assertEquals(StatusCode.RETRY, locks.upgrade(first, LockMode.EXCLUSIVE, 0, 0));
    assertEquals(StatusCode.OK, locks.release(second));
    assertEquals(StatusCode.OK, locks.upgrade(first, LockMode.EXCLUSIVE, 0, 0));
    assertEquals(
        StatusCode.RETRY,
        locks.tryAcquire(33, LockScope.KEY, 1, 7, LockMode.SHARED, 0, 0, writer));
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
        locks.tryAcquire(41, LockScope.KEY, 1, 8, LockMode.EXCLUSIVE, 0, 0, owner));
    assertEquals(
        StatusCode.RETRY,
        locks.tryAcquire(
            42, LockScope.KEY, 1, 8, LockMode.EXCLUSIVE, 0, 0, firstWaiter));
    assertEquals(
        StatusCode.RETRY,
        locks.tryAcquire(
            43, LockScope.KEY, 1, 8, LockMode.EXCLUSIVE, 0, 0, secondWaiter));
    assertEquals(2, locks.waitingCount());
    assertEquals(StatusCode.OK, locks.release(owner));
    assertEquals(
        StatusCode.RETRY,
        locks.tryAcquire(
            43, LockScope.KEY, 1, 8, LockMode.EXCLUSIVE, 0, 0, secondWaiter));
    assertEquals(
        StatusCode.OK,
        locks.tryAcquire(
            42, LockScope.KEY, 1, 8, LockMode.EXCLUSIVE, 0, 0, firstWaiter));
    assertEquals(1, locks.waitingCount());
    assertEquals(StatusCode.OK, locks.release(firstWaiter));
    assertEquals(
        StatusCode.OK,
        locks.tryAcquire(
            43, LockScope.KEY, 1, 8, LockMode.EXCLUSIVE, 0, 0, secondWaiter));
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
        locks.tryAcquire(51, LockScope.KEY, 1, 10, LockMode.EXCLUSIVE, 0, 0, firstA));
    assertEquals(
        StatusCode.OK,
        locks.tryAcquire(52, LockScope.KEY, 1, 20, LockMode.EXCLUSIVE, 0, 0, secondB));
    assertEquals(
        StatusCode.RETRY,
        locks.tryAcquire(51, LockScope.KEY, 1, 20, LockMode.EXCLUSIVE, 0, 0, firstB));
    assertEquals(
        StatusCode.CONFLICT,
        locks.tryAcquire(52, LockScope.KEY, 1, 10, LockMode.EXCLUSIVE, 0, 0, secondA));
    assertEquals(true, locks.isDeadlockVictim(52));
    assertEquals(false, locks.isDeadlockVictim(51));
    assertEquals(1, locks.waitingCount());
    assertEquals(StatusCode.OK, locks.release(secondB));
    locks.transactionCompleted(52);
    assertEquals(
        StatusCode.OK,
        locks.tryAcquire(51, LockScope.KEY, 1, 20, LockMode.EXCLUSIVE, 0, 0, firstB));
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
        locks.tryAcquire(71, LockScope.KEY, 1, 20, LockMode.EXCLUSIVE, 0, 0, olderB));
    assertEquals(
        StatusCode.OK,
        locks.tryAcquire(72, LockScope.KEY, 1, 10, LockMode.EXCLUSIVE, 0, 0, youngerA));
    assertEquals(
        StatusCode.RETRY,
        locks.tryAcquire(72, LockScope.KEY, 1, 20, LockMode.EXCLUSIVE, 0, 0, youngerB));
    assertEquals(
        StatusCode.RETRY,
        locks.tryAcquire(71, LockScope.KEY, 1, 10, LockMode.EXCLUSIVE, 0, 0, olderA));
    assertEquals(true, locks.isDeadlockVictim(72));
    assertEquals(
        StatusCode.CONFLICT,
        locks.tryAcquire(72, LockScope.KEY, 1, 20, LockMode.EXCLUSIVE, 0, 0, youngerB));
    assertEquals(StatusCode.OK, locks.release(youngerA));
    locks.transactionCompleted(72);
    assertEquals(
        StatusCode.OK,
        locks.tryAcquire(71, LockScope.KEY, 1, 10, LockMode.EXCLUSIVE, 0, 0, olderA));
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
        locks.tryAcquire(61, LockScope.KEY, 1, 30, LockMode.SHARED, 0, 0, first));
    assertEquals(
        StatusCode.OK,
        locks.tryAcquire(62, LockScope.KEY, 1, 30, LockMode.SHARED, 0, 0, second));
    assertEquals(StatusCode.RETRY, locks.upgrade(first, LockMode.EXCLUSIVE, 0, 0));
    assertEquals(StatusCode.CONFLICT, locks.upgrade(second, LockMode.EXCLUSIVE, 0, 0));
    assertEquals(true, locks.isDeadlockVictim(62));
    assertEquals(StatusCode.OK, locks.release(second));
    locks.transactionCompleted(62);
    assertEquals(StatusCode.OK, locks.upgrade(first, LockMode.EXCLUSIVE, 0, 0));
    assertEquals(StatusCode.OK, locks.release(first));
  }
}
