package io.riverdb.server;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** A bounded synthetic worker; no database operation is stalled by this fixture. */
public final class StalledShutdownWorker {
  private final CountDownLatch release = new CountDownLatch(1);
  private final LoopbackRiverServer server;
  private final Thread worker;

  public StalledShutdownWorker(LoopbackRiverServer server) {
    this.server = server;
    worker = Thread.ofPlatform().daemon().start(() -> {
      try { release.await(8, TimeUnit.SECONDS); }
      catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    });
    synchronized (server) { server.slots[0].worker = worker; }
  }

  public void close() throws InterruptedException {
    release.countDown();
    worker.join(1_000);
    assertFalse(worker.isAlive());
    assertFalse(server.acceptor.isAlive());
  }
}
