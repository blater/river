package io.riverdb.bench.tpcc;

import io.riverdb.base.error.StatusCode;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicLong;

/** One terminal's reusable transaction-attempt correlation state. */
final class TpccAttemptAccounting implements TpccRetryObserver {
  private static final AtomicLong NEXT_ATTEMPT_ID = new AtomicLong();

  private final int terminal;
  private long logicalSequence;
  private long currentAttemptId;
  private long deadline;
  private int attempt;
  private TpccMetrics metrics;
  private TpccTransactionType type;
  private TpccSession session;
  private long metricsEpoch;

  TpccAttemptAccounting(int terminalIndex) {
    if (terminalIndex < 0) throw new IllegalArgumentException("negative terminal index");
    terminal = terminalIndex;
  }

  void begin(
      TpccTransactionType transactionType,
      TpccMetrics target,
      long measuredDeadline,
      TpccSession diagnosticSession,
      long diagnosticEpoch) {
    if (transactionType == null || measuredDeadline <= 0
        || diagnosticSession == null || diagnosticEpoch <= 0) {
      throw new IllegalArgumentException("invalid attempt accounting boundary");
    }
    if (logicalSequence == Long.MAX_VALUE) {
      throw new IllegalStateException("logical transaction sequence exhausted");
    }
    logicalSequence++;
    currentAttemptId = 0;
    attempt = 0;
    type = transactionType;
    metrics = target;
    deadline = measuredDeadline;
    session = diagnosticSession;
    metricsEpoch = diagnosticEpoch;
  }

  @Override
  public void attemptStarted(int attemptNumber) throws SQLException {
    if (attemptNumber < 1 || attemptNumber != attempt + 1) {
      throw new IllegalStateException("attempt sequence is not monotonic");
    }
    attempt = attemptNumber;
    currentAttemptId = nextAttemptId();
    session.beginDiagnosticAttempt(currentAttemptId, metricsEpoch);
    if (metrics != null) metrics.attemptStarted(type, currentAttemptId, measured());
  }

  @Override
  public void retryableOutcome(
      StatusCode status, boolean clientWillRetry, boolean measuredOutcome)
      throws SQLException {
    if (status == null || !status.isRetryable() || currentAttemptId == 0) {
      throw new IllegalStateException("invalid retryable outcome correlation");
    }
    if (metrics != null) {
      metrics.retryableOutcome(
          type, status, clientWillRetry, measuredOutcome,
          currentAttemptId, logicalSequence, terminal, attempt,
          session.diagnosticStepTag());
    }
  }

  long logicalSequence() { return logicalSequence; }

  long currentAttemptId() { return currentAttemptId; }

  int attempt() { return attempt; }

  int terminal() { return terminal; }

  private boolean measured() { return System.nanoTime() <= deadline; }

  private static long nextAttemptId() {
    while (true) {
      long current = NEXT_ATTEMPT_ID.get();
      if (current == Long.MAX_VALUE) {
        throw new IllegalStateException("attempt identifier address space exhausted");
      }
      long next = current + 1;
      if (NEXT_ATTEMPT_ID.compareAndSet(current, next)) return next;
    }
  }
}
