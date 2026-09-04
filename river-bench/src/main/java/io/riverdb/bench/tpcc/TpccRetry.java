package io.riverdb.bench.tpcc;

import io.riverdb.base.error.StatusCode;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.LockSupport;

/** Whole-transaction retry for River's serialization/deadlock SQL state. */
final class TpccRetry {
  record Result(boolean committed, boolean retryExhausted, int retries) {}

  private TpccRetry() {}

  static Result execute(
      TpccAttempt attempt,
      TpccConfig config,
      long deadline,
      TpccRetryObserver observer) throws SQLException {
    if (observer == null) throw new IllegalArgumentException("retry observer is required");
    for (int current = 1; current <= config.maximumAttempts(); current++) {
      observer.attemptStarted(current);
      try {
        return new Result(attempt.execute(), false, current - 1);
      } catch (SQLException failure) {
        StatusCode status = TpccStatusCodes.decode(failure);
        if (status == null || !status.isRetryable()) throw failure;
        boolean measuredOutcome = System.nanoTime() <= deadline;
        boolean retry = current < config.maximumAttempts() && System.nanoTime() < deadline;
        if (!retry) {
          observer.retryableOutcome(status, false, measuredOutcome);
          return new Result(false, true, current - 1);
        }
        if (!backoff(config, current, deadline)) {
          observer.retryableOutcome(status, false, measuredOutcome);
          return new Result(false, true, current - 1);
        }
        observer.retryableOutcome(status, true, measuredOutcome);
      }
    }
    throw new AssertionError("unreachable retry state");
  }

  private static boolean backoff(
      TpccConfig config, int attempt, long deadline) throws SQLException {
    long remaining = deadline - System.nanoTime();
    if (remaining <= 0) return false;
    long cap = exponentialDelayCap(
        saturatedNanos(config.retryBase()), saturatedNanos(config.retryMaximum()), attempt);
    long delay = randomDelay(Math.min(cap, remaining));
    LockSupport.parkNanos(delay);
    if (Thread.interrupted()) throw new SQLException("TPC-C terminal interrupted", "57014");
    return System.nanoTime() < deadline;
  }

  static long exponentialDelayCap(long base, long maximum, int attempt) {
    if (base <= 0 || maximum < base || attempt < 1) {
      throw new IllegalArgumentException("invalid retry delay bounds");
    }
    long delay = base;
    for (int current = 1; current < attempt && delay < maximum; current++) {
      if (delay > maximum - delay) return maximum;
      delay += delay;
    }
    return delay;
  }

  static long saturatedNanos(Duration duration) {
    try {
      return duration.toNanos();
    } catch (ArithmeticException overflow) {
      return Long.MAX_VALUE;
    }
  }

  static long randomDelay(long inclusiveMaximum) {
    if (inclusiveMaximum < 0) throw new IllegalArgumentException("negative retry delay");
    if (inclusiveMaximum == Long.MAX_VALUE) {
      return ThreadLocalRandom.current().nextLong() & Long.MAX_VALUE;
    }
    return ThreadLocalRandom.current().nextLong(inclusiveMaximum + 1);
  }
}
