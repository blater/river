package io.riverdb.engine.table;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.tx.Transaction;
import io.riverdb.tx.TransactionManager;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.lock.LockMode;
import io.riverdb.tx.api.lock.LockToken;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class IndexedLockWaitTest {
  @Test
  void deadlockRevokedBorrowedTokenUnwindsAsCompletedCleanup() throws Exception {
    TransactionManager manager = new TransactionManager(71, 73, 1, 4);
    Transaction older = new Transaction(4);
    Transaction younger = new Transaction(4);
    assertEquals(StatusCode.OK,
        manager.begin(IsolationLevel.REPEATABLE_READ, 0, older));
    assertEquals(StatusCode.OK,
        manager.begin(IsolationLevel.REPEATABLE_READ, 0, younger));
    IndexedLockWait olderWait = new IndexedLockWait(manager);
    IndexedLockWait youngerWait = new IndexedLockWait(manager);
    LockToken borrowed = new LockToken();
    assertEquals(StatusCode.OK, youngerWait.acquireBorrowedKey(
        younger, 7, 10, LockMode.EXCLUSIVE, borrowed));
    assertEquals(StatusCode.OK,
        youngerWait.acquireKey(younger, 7, 20, LockMode.EXCLUSIVE));
    assertEquals(StatusCode.OK,
        olderWait.acquireKey(older, 7, 30, LockMode.EXCLUSIVE));

    ExecutorService executor = Executors.newSingleThreadExecutor();
    AtomicReference<Thread> worker = new AtomicReference<>();
    try {
      Future<StatusCode> youngerBlocked = executor.submit(() -> {
        worker.set(Thread.currentThread());
        return youngerWait.acquireKey(younger, 7, 30, LockMode.EXCLUSIVE);
      });
      awaitParked(worker, youngerBlocked);
      assertEquals(StatusCode.OK,
          olderWait.acquireKey(older, 7, 20, LockMode.EXCLUSIVE));
      assertEquals(StatusCode.DEADLOCK, youngerBlocked.get(1, TimeUnit.SECONDS));
      assertTrue(borrowed.isActive());
      assertEquals(StatusCode.OK, youngerWait.release(younger, borrowed));
      assertFalse(borrowed.isActive());
    } finally {
      executor.shutdownNow();
    }
  }

  private static void awaitParked(AtomicReference<Thread> worker, Future<?> future) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
    Thread.State state = Thread.State.NEW;
    while (!future.isDone() && System.nanoTime() < deadline) {
      Thread thread = worker.get();
      if (thread != null) {
        state = thread.getState();
        if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) break;
      }
      Thread.onSpinWait();
    }
    assertFalse(future.isDone());
    assertTrue(state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING);
  }
}
