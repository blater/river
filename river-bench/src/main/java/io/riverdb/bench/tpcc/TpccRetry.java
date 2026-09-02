package io.riverdb.bench.tpcc;

import java.sql.SQLException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.LockSupport;

/** Whole-transaction retry for River's serialization/deadlock SQL state. */
final class TpccRetry {
  record Result(boolean committed, boolean retryExhausted, int retries) {}

  private TpccRetry() {}

  static Result execute(TpccAttempt attempt, TpccConfig config, long deadline) throws SQLException {
    for (int current = 1; current <= config.maximumAttempts(); current++) {
      try {
        return new Result(attempt.execute(), false, current - 1);
      } catch (SQLException failure) {
        if (!"40001".equals(failure.getSQLState())) throw failure;
        if (current == config.maximumAttempts() || System.nanoTime() >= deadline) {
          return new Result(false, true, current - 1);
        }
        if (!backoff(config, current, deadline)) {
          return new Result(false, true, current - 1);
        }
      }
    }
    throw new AssertionError("unreachable retry state");
  }

  private static boolean backoff(
      TpccConfig config, int attempt, long deadline) throws SQLException {
    long exponent = 1L << Math.min(attempt - 1, 20);
    long cap = Math.min(config.retryMaximum().toNanos(),
        saturatedMultiply(config.retryBase().toNanos(), exponent));
    long delay = ThreadLocalRandom.current().nextLong(cap + 1);
    long remaining = deadline - System.nanoTime();
    if (remaining <= 0) return false;
    LockSupport.parkNanos(Math.min(delay, remaining));
    if (Thread.interrupted()) throw new SQLException("TPC-C terminal interrupted", "57014");
    return System.nanoTime() < deadline;
  }

  private static long saturatedMultiply(long value, long multiplier) {
    if (value > Long.MAX_VALUE / multiplier) return Long.MAX_VALUE;
    return value * multiplier;
  }
}
