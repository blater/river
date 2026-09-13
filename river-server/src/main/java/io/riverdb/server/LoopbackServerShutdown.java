package io.riverdb.server;

import io.riverdb.base.error.StatusCode;
import java.io.IOException;

/** Bounded shutdown of the listener, workers, and sockets. */
final class LoopbackServerShutdown {
  private static final int SHUTDOWN_TIMEOUT_MILLIS = 5_000;

  private LoopbackServerShutdown() { }

  static StatusCode close(LoopbackRiverServer server) {
    long deadline = System.nanoTime() + SHUTDOWN_TIMEOUT_MILLIS * 1_000_000L;
    server.running = false;
    StatusCode status = closeListener(server);
    status = joinUntil(server.acceptor, deadline, status);
    Thread[] workers = new Thread[server.slots.length];
    synchronized (server) {
      for (int index = 0; index < server.slots.length; index++) {
        LoopbackRiverServer.ConnectionSlot slot = server.slots[index];
        workers[index] = slot.worker;
        slot.cancellation.cancel();
        if (slot.socket != null) {
          try {
            slot.socket.close();
          } catch (IOException failure) {
            status = StatusCode.IO_FAILURE;
          }
        }
      }
    }
    for (Thread worker : workers) {
      status = joinUntil(worker, deadline, status);
    }
    if (server.acceptor != null && server.acceptor.isAlive()) {
      server.lastStatus = StatusCode.TIMEOUT;
      return StatusCode.TIMEOUT;
    }
    for (Thread worker : workers) {
      if (worker != null && worker.isAlive()) {
        server.lastStatus = StatusCode.TIMEOUT;
        return StatusCode.TIMEOUT;
      }
    }
    if (server.validityFence != null) {
      StatusCode fenceStatus = server.validityFence.close();
      if (status.isOk() && !fenceStatus.isOk()) status = fenceStatus;
    }
    if (!status.isOk()) {
      server.lastStatus = status;
    }
    return status;
  }

  private static StatusCode closeListener(LoopbackRiverServer server) {
    try {
      server.listener.close();
      return StatusCode.OK;
    } catch (IOException failure) {
      return StatusCode.IO_FAILURE;
    }
  }

  private static StatusCode joinUntil(Thread thread, long deadline, StatusCode current) {
    if (thread == null) {
      return current;
    }
    long remaining = deadline - System.nanoTime();
    if (remaining <= 0) {
      return thread.isAlive() ? StatusCode.TIMEOUT : current;
    }
    try {
      thread.join((remaining + 999_999L) / 1_000_000L);
      return thread.isAlive() ? StatusCode.TIMEOUT : current;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return StatusCode.CANCELLED;
    }
  }

}
