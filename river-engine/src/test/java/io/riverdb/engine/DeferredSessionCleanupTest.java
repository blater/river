package io.riverdb.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class DeferredSessionCleanupTest {
  @Test
  void nonretryableSessionDoesNotBlockLaterCleanupAndCanBeRecovered() {
    Object owner = new Object();
    DeferredSessionCleanup cleanup = new DeferredSessionCleanup(owner);
    Target fenced = new Target(owner, StatusCode.IO_FAILURE);
    Target later = new Target(owner, StatusCode.OK);

    assertEquals(StatusCode.OK, cleanup.transfer(fenced));
    awaitHealth(cleanup, StatusCode.IO_FAILURE);
    assertEquals(StatusCode.OK, cleanup.transfer(later));
    awaitAttempts(later, 1);
    awaitHealth(cleanup, StatusCode.IO_FAILURE);

    fenced.status = StatusCode.OK;
    cleanup.retryFence();
    awaitHealth(cleanup, StatusCode.OK);
    assertEquals(StatusCode.OK, cleanup.close());
  }

  @Test
  void retryableCleanupEventuallyClosesWithoutAllocatingQueueNodes() {
    Object owner = new Object();
    DeferredSessionCleanup cleanup = new DeferredSessionCleanup(owner);
    Target target = new Target(owner, StatusCode.RETRY);
    assertEquals(StatusCode.OK, cleanup.transfer(target));
    awaitAttempts(target, 1);
    target.status = StatusCode.OK;
    awaitHealth(cleanup, StatusCode.OK);
    assertTrue(target.attempts.get() >= 2);
    assertEquals(StatusCode.OK, cleanup.close());
  }

  private static void awaitAttempts(Target target, int attempts) {
    long deadline = System.nanoTime() + 2_000_000_000L;
    while (target.attempts.get() < attempts && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    assertTrue(target.attempts.get() >= attempts);
  }

  private static void awaitHealth(DeferredSessionCleanup cleanup, StatusCode expected) {
    long deadline = System.nanoTime() + 2_000_000_000L;
    StatusCode status;
    do {
      status = cleanup.health();
      if (status == expected) return;
      Thread.onSpinWait();
    } while (System.nanoTime() < deadline);
    assertEquals(expected, status);
  }

  private static final class Target implements TerminalSessionCleanupTarget {
    private final Object owner;
    private final AtomicInteger attempts = new AtomicInteger();
    private volatile StatusCode status;
    private TerminalSessionCleanupTarget next;
    private boolean transferred;

    private Target(Object databaseOwner, StatusCode closeStatus) {
      owner = databaseOwner;
      status = closeStatus;
    }

    @Override
    public boolean transferToTerminalCleanup(Object databaseOwner) {
      if (transferred || databaseOwner != owner) return false;
      transferred = true;
      return true;
    }

    @Override
    public StatusCode retryTerminalClose() {
      attempts.incrementAndGet();
      return status;
    }

    @Override
    public TerminalSessionCleanupTarget terminalCleanupNext() { return next; }

    @Override
    public void terminalCleanupNext(TerminalSessionCleanupTarget target) { next = target; }
  }
}
