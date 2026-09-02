package io.riverdb.bench.tpcc;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/** Opens, coordinates, combines, and closes the bounded terminal set. */
final class TpccTerminalRunner {
  private TpccTerminalRunner() {}

  static TpccMetrics run(TpccConfig config) throws Exception {
    List<TpccTerminal> terminals = new ArrayList<>(config.terminals());
    try {
      for (int index = 0; index < config.terminals(); index++) {
        terminals.add(new TpccTerminal(config, index));
      }
      return execute(config, terminals);
    } finally {
      SQLException failure = null;
      for (TpccTerminal terminal : terminals) {
        try { terminal.close(); } catch (SQLException exception) { failure = exception; }
      }
      if (failure != null) throw failure;
    }
  }

  private static TpccMetrics execute(TpccConfig config, List<TpccTerminal> terminals)
      throws Exception {
    try (ExecutorService executor = Executors.newFixedThreadPool(config.terminals())) {
      System.out.println("phase_start=warmup");
      executeWarmup(config, terminals, executor);
      System.out.println("phase_complete=warmup");
      System.out.println("phase_start=measured");
      return executeMeasured(config, terminals, executor);
    }
  }

  private static void executeWarmup(
      TpccConfig config, List<TpccTerminal> terminals, ExecutorService executor)
      throws Exception {
    long deadline = System.nanoTime() + config.warmup().toNanos();
    List<Future<TpccMetrics>> futures = new ArrayList<>(config.terminals());
    for (TpccTerminal terminal : terminals) {
      futures.add(executor.submit(() -> terminal.run(deadline, false)));
    }
    for (Future<TpccMetrics> future : futures) result(future);
  }

  private static TpccMetrics executeMeasured(
      TpccConfig config, List<TpccTerminal> terminals, ExecutorService executor)
      throws Exception {
    CountDownLatch ready = new CountDownLatch(config.terminals());
    CountDownLatch start = new CountDownLatch(1);
    AtomicLong deadline = new AtomicLong();
    List<Future<TpccMetrics>> futures = new ArrayList<>(config.terminals());
    for (TpccTerminal terminal : terminals) {
      futures.add(executor.submit(() -> {
        ready.countDown();
        start.await();
        return terminal.run(deadline.get(), true);
      }));
    }
    ready.await();
    TpccJfrProfile profile = TpccJfrProfile.open(config);
    try {
      long measuredDeadline = System.nanoTime() + config.measured().toNanos();
      deadline.set(measuredDeadline);
      start.countDown();
      awaitDeadline(measuredDeadline);
      long inFlightAtCutoff = 0;
      for (TpccTerminal terminal : terminals) {
        if (terminal.transactionActive()) inFlightAtCutoff++;
      }
      profile.close();
      TpccMetrics combined = new TpccMetrics();
      for (Future<TpccMetrics> future : futures) combined.add(result(future));
      combined.inFlightAtCutoff(inFlightAtCutoff);
      return combined;
    } finally {
      start.countDown();
      profile.close();
    }
  }

  private static void awaitDeadline(long deadline) throws InterruptedException {
    long remaining;
    while ((remaining = deadline - System.nanoTime()) > 0) {
      LockSupport.parkNanos(remaining);
      if (Thread.interrupted()) throw new InterruptedException("TPC-C coordinator interrupted");
    }
  }

  private static TpccMetrics result(Future<TpccMetrics> future) throws Exception {
    try {
      return future.get();
    } catch (ExecutionException failure) {
      Throwable cause = failure.getCause();
      if (cause instanceof Exception exception) throw exception;
      throw failure;
    }
  }
}
