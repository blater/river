package io.riverdb.wal.local;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.ForceMode;
import java.util.concurrent.locks.LockSupport;

/** One bounded command/result slot that performs only captured local provider force I/O. */
final class LocalWalForceWorker {
  private final Thread worker;
  private final Thread completionOwner;
  private LocalWalForceTarget target;
  private boolean commandReady;
  private boolean resultReady;
  private boolean closing;
  private boolean stopped;

  LocalWalForceWorker(Thread owner) {
    completionOwner = owner;
    worker = Thread.ofVirtual()
        .name("river-wal-force-" + Integer.toHexString(System.identityHashCode(this)))
        .unstarted(this::run);
    worker.start();
  }

  synchronized StatusCode submit(LocalWalForceTarget captured, LocalWalForceCause forceCause) {
    if (captured == null || forceCause == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (closing) return StatusCode.CLOSED;
    if (commandReady || resultReady || target != null) return StatusCode.CONFLICT;
    target = captured;
    commandReady = true;
    notifyAll();
    return StatusCode.OK;
  }

  synchronized boolean resultReady(LocalWalForceTarget expected) {
    return resultReady && target == expected;
  }

  synchronized StatusCode consume(LocalWalForceTarget expected) {
    if (!resultReady || target != expected) return StatusCode.RETRY;
    resultReady = false;
    target = null;
    return StatusCode.OK;
  }

  synchronized StatusCode close() {
    if (!closing) {
      closing = true;
      notifyAll();
    }
    boolean interrupted = false;
    while (!stopped) {
      try {
        wait();
      } catch (InterruptedException wakeup) {
        interrupted = true;
      }
    }
    if (interrupted) Thread.currentThread().interrupt();
    return StatusCode.OK;
  }

  private void run() {
    while (true) {
      LocalWalForceTarget captured;
      synchronized (this) {
        while (!commandReady && !closing) {
          try {
            wait();
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            closing = true;
          }
        }
        if (!commandReady && closing) {
          stopped = true;
          notifyAll();
          return;
        }
        captured = target;
        commandReady = false;
      }
      long started = System.nanoTime();
      StatusCode status;
      try {
        status = captured.file().force(
            captured.startOffset(), captured.endOffset(), ForceMode.CONTENT_AND_METADATA);
      } catch (Throwable unexpected) {
        status = StatusCode.INVARIANT_BROKEN;
      }
      captured.completeForceIo(status, System.nanoTime() - started);
      synchronized (this) {
        resultReady = true;
        notifyAll();
      }
      LockSupport.unpark(completionOwner);
    }
  }
}
