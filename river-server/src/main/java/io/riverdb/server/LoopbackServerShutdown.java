package io.riverdb.server;

import io.riverdb.base.error.StatusCode;
import java.io.IOException;

/** Bounded shutdown of the listener, workers, sockets, and audit log. */
final class LoopbackServerShutdown {
  private static final int SHUTDOWN_TIMEOUT_MILLIS = 5_000;

  private LoopbackServerShutdown() { }

  static StatusCode close(LoopbackRiverServer server) {
    server.running = false;
    StatusCode status = closeListener(server);
    long deadline = System.nanoTime() + SHUTDOWN_TIMEOUT_MILLIS * 1_000_000L;
    status = joinUntil(server.acceptor, deadline, status);
    Thread[] workers = new Thread[server.slots.length];
    synchronized (server) {
      for (int index = 0; index < server.slots.length; index++) {
        LoopbackRiverServer.ConnectionSlot slot = server.slots[index];
        workers[index] = slot.worker;
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
    if (server.audit != null) {
      StatusCode auditStatus = server.audit.close();
      if (status.isOk() && auditStatus != StatusCode.CLOSED) {
        status = auditStatus;
      }
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
