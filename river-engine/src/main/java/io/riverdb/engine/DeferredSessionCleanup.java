package io.riverdb.engine;

import io.riverdb.base.error.StatusCode;
import java.util.concurrent.locks.LockSupport;

/** Database-owned intrusive retry queue for unreachable sessions. */
final class DeferredSessionCleanup implements Runnable {
  private static final long INITIAL_RETRY_BACKOFF_NANOS = 100_000;
  private static final long MAXIMUM_RETRY_BACKOFF_NANOS = 100_000_000;
  private final Object owner;
  private final Thread worker;
  private TerminalSessionCleanupTarget head;
  private TerminalSessionCleanupTarget tail;
  private TerminalSessionCleanupTarget fencedHead;
  private TerminalSessionCleanupTarget fencedTail;
  private TerminalSessionCleanupTarget active;
  private StatusCode fence;
  private boolean stopping;

  DeferredSessionCleanup(Object databaseOwner) {
    owner = databaseOwner;
    worker = Thread.ofVirtual().name("river-terminal-session-cleanup").unstarted(this);
    worker.start();
  }

  synchronized StatusCode transfer(TerminalSessionCleanupTarget target) {
    if (stopping) return StatusCode.CLOSED;
    if (target == null || !target.transferToTerminalCleanup(owner)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    append(target);
    notifyAll();
    return StatusCode.OK;
  }

  synchronized StatusCode health() {
    if (fence != null) return fence;
    return head == null && active == null ? StatusCode.OK : StatusCode.RETRY;
  }

  synchronized void retryFence() {
    if (fencedHead != null) {
      if (tail == null) head = fencedHead;
      else tail.terminalCleanupNext(fencedHead);
      tail = fencedTail;
      fencedHead = null;
      fencedTail = null;
    }
    fence = null;
    notifyAll();
  }

  StatusCode close() {
    synchronized (this) {
      StatusCode health = health();
      if (!health.isOk()) return health.isRetryable() ? StatusCode.RETRY : health;
      stopping = true;
      notifyAll();
    }
    try {
      worker.join();
      return StatusCode.OK;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return StatusCode.CANCELLED;
    }
  }

  @Override
  public void run() {
    long backoff = INITIAL_RETRY_BACKOFF_NANOS;
    while (true) {
      TerminalSessionCleanupTarget target = take();
      if (target == null) return;
      StatusCode status = target.retryTerminalClose();
      if (status.isOk() || status == StatusCode.CLOSED) {
        complete();
        backoff = INITIAL_RETRY_BACKOFF_NANOS;
      } else if (status.isRetryable()) {
        retry(target);
        LockSupport.parkNanos(backoff);
        backoff = Math.min(MAXIMUM_RETRY_BACKOFF_NANOS, backoff << 1);
      } else {
        fence(target, status);
        backoff = INITIAL_RETRY_BACKOFF_NANOS;
      }
    }
  }

  private synchronized TerminalSessionCleanupTarget take() {
    while (head == null && !stopping) {
      try {
        wait();
      } catch (InterruptedException interrupted) {
        // Terminal ownership cannot be abandoned by worker interruption.
      }
    }
    if (head == null) return null;
    active = head;
    head = active.terminalCleanupNext();
    active.terminalCleanupNext(null);
    if (head == null) tail = null;
    return active;
  }

  private synchronized void complete() {
    active = null;
    notifyAll();
  }

  private synchronized void retry(TerminalSessionCleanupTarget target) {
    active = null;
    append(target);
    notifyAll();
  }

  private synchronized void fence(TerminalSessionCleanupTarget target, StatusCode status) {
    active = null;
    if (fencedTail == null) fencedHead = target;
    else fencedTail.terminalCleanupNext(target);
    fencedTail = target;
    if (fence == null) fence = status;
    notifyAll();
  }

  private void append(TerminalSessionCleanupTarget target) {
    if (tail == null) head = target;
    else tail.terminalCleanupNext(target);
    tail = target;
  }
}
