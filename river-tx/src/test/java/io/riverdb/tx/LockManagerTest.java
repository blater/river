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
}
