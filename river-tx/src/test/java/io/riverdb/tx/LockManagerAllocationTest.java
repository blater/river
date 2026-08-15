package io.riverdb.tx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.management.ThreadMXBean;
import io.riverdb.base.error.StatusCode;
import io.riverdb.tx.api.lock.LockMode;
import io.riverdb.tx.api.lock.LockScope;
import io.riverdb.tx.api.lock.LockToken;
import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

final class LockManagerAllocationTest {
  private static volatile long allocationGuard;

  @Test
  void warmedContendedWaitAndCancellationReuseState() {
    java.lang.management.ThreadMXBean standard = ManagementFactory.getThreadMXBean();
    Assumptions.assumeTrue(standard instanceof ThreadMXBean);
    ThreadMXBean bean = (ThreadMXBean) standard;
    Assumptions.assumeTrue(bean.isThreadAllocatedMemorySupported());
    bean.setThreadAllocatedMemoryEnabled(true);
    LockManager locks = new LockManager(4);
    LockToken owner = new LockToken();
    LockToken waiter = new LockToken();
    assertEquals(
        StatusCode.OK,
        locks.tryAcquire(
            1, LockScope.KEY, 7, 9, 7, 9, LockMode.EXCLUSIVE, 0, 0, owner));
    for (int index = 0; index < 1_000; index++) {
      exercise(locks, waiter);
    }
    long threadId = Thread.currentThread().threadId();
    long before = bean.getThreadAllocatedBytes(threadId);
    for (int index = 0; index < 10_000; index++) {
      exercise(locks, waiter);
    }
    long allocated = bean.getThreadAllocatedBytes(threadId) - before;
    assertTrue(allocated <= 512, "warmed lock wait allocated bytes: " + allocated);
    assertEquals(0, locks.waitingCount());
    assertEquals(StatusCode.OK, locks.release(owner));
  }

  private static void exercise(LockManager locks, LockToken waiter) {
    allocationGuard += locks.tryAcquire(
        2, LockScope.KEY, 7, 9, 7, 9, LockMode.EXCLUSIVE, 0, 0, waiter).ordinal();
    locks.cancelWait(2);
  }
}
