package io.riverdb.bench.tpcc;

import io.riverdb.base.error.StatusCode;

/** Owns measured/drain attempt, retry outcome, and correlation accounting. */
final class TpccRetryMetrics {
  private static final TpccTransactionType[] TRANSACTION_TYPES = TpccTransactionType.values();
  private static final StatusCode[] STATUS_VALUES = StatusCode.values();
  private static final int TYPE_COUNT = TRANSACTION_TYPES.length;
  private static final int STATUS_COUNT = STATUS_VALUES.length;
  private final long[] attemptsStarted = new long[TYPE_COUNT];
  private final long[] drainAttemptsStarted = new long[TYPE_COUNT];
  private final long[][] retryableOutcomes = new long[TYPE_COUNT][STATUS_COUNT];
  private final long[][] clientRetries = new long[TYPE_COUNT][STATUS_COUNT];
  private final long[][] drainRetryableOutcomes = new long[TYPE_COUNT][STATUS_COUNT];
  private final long[][] drainClientRetries = new long[TYPE_COUNT][STATUS_COUNT];
  private long firstAttemptId = Long.MAX_VALUE;
  private long lastAttemptId;
  private boolean attemptObserved;
  private long unclassifiedRetryFailures;
  private long drainUnclassifiedRetryFailures;
  private long retryCorrelationOverflows;
  private long retryCorrelationCount;
  private boolean overflowed;

  void attemptStarted(TpccTransactionType type, long attemptId, boolean measured) {
    if (attemptId <= 0) throw new IllegalArgumentException("attempt ID must be positive");
    int index = type.ordinal();
    if (measured) {
      attemptsStarted[index] = increment(attemptsStarted[index]);
      firstAttemptId = attemptObserved ? Math.min(firstAttemptId, attemptId) : attemptId;
      lastAttemptId = Math.max(lastAttemptId, attemptId);
      attemptObserved = true;
    } else {
      drainAttemptsStarted[index] = increment(drainAttemptsStarted[index]);
    }
  }

  void retryableOutcome(
      TpccTransactionType type,
      StatusCode status,
      boolean clientWillRetry,
      boolean measured,
      long attemptTag,
      long logicalSequence,
      int terminal,
      int attemptNumber,
      long stepTag) {
    if (status == null || !status.isRetryable()) {
      throw new IllegalArgumentException("status is not retryable");
    }
    long[][] outcomes = measured ? retryableOutcomes : drainRetryableOutcomes;
    long[][] retryDecisions = measured ? clientRetries : drainClientRetries;
    int typeIndex = type.ordinal();
    int statusIndex = status.ordinal();
    outcomes[typeIndex][statusIndex] = increment(outcomes[typeIndex][statusIndex]);
    if (clientWillRetry) {
      retryDecisions[typeIndex][statusIndex] = increment(
          retryDecisions[typeIndex][statusIndex]);
    }
    recordRetryCorrelation(
        type, status, clientWillRetry, measured,
        attemptTag, logicalSequence, terminal, attemptNumber, stepTag);
  }

  void unclassifiedRetryFailure(boolean measured) {
    if (measured) unclassifiedRetryFailures = increment(unclassifiedRetryFailures);
    else drainUnclassifiedRetryFailures = increment(drainUnclassifiedRetryFailures);
  }

  void add(TpccRetryMetrics source) {
    addCounts(attemptsStarted, source.attemptsStarted);
    addCounts(drainAttemptsStarted, source.drainAttemptsStarted);
    addCounts(retryableOutcomes, source.retryableOutcomes);
    addCounts(clientRetries, source.clientRetries);
    addCounts(drainRetryableOutcomes, source.drainRetryableOutcomes);
    addCounts(drainClientRetries, source.drainClientRetries);
    unclassifiedRetryFailures = add(unclassifiedRetryFailures, source.unclassifiedRetryFailures);
    drainUnclassifiedRetryFailures = add(
        drainUnclassifiedRetryFailures, source.drainUnclassifiedRetryFailures);
    retryCorrelationCount = add(retryCorrelationCount, source.retryCorrelationCount);
    retryCorrelationOverflows = add(
        retryCorrelationOverflows, source.retryCorrelationOverflows);
    if (source.attemptObserved) {
      firstAttemptId = attemptObserved
          ? Math.min(firstAttemptId, source.firstAttemptId) : source.firstAttemptId;
      lastAttemptId = Math.max(lastAttemptId, source.lastAttemptId);
      attemptObserved = true;
    }
    overflowed |= source.overflowed;
  }

  long retries() { return sum(clientRetries); }
  long retries(TpccTransactionType type) { return sum(clientRetries[type.ordinal()]); }

  long retryableOutcomes(StatusCode status) {
    long total = 0;
    for (TpccTransactionType type : TRANSACTION_TYPES) {
      total = add(total, retryableOutcomes(type, status));
    }
    return total;
  }

  long retryableOutcomes() {
    long total = 0;
    for (StatusCode status : STATUS_VALUES) total = add(total, retryableOutcomes(status));
    return total;
  }

  long retryableOutcomes(TpccTransactionType type, StatusCode status) {
    return retryableOutcomes[type.ordinal()][status.ordinal()];
  }

  long clientRetries(StatusCode status) {
    long total = 0;
    for (TpccTransactionType type : TRANSACTION_TYPES) {
      total = add(total, clientRetries(type, status));
    }
    return total;
  }

  long clientRetries(TpccTransactionType type, StatusCode status) {
    return clientRetries[type.ordinal()][status.ordinal()];
  }

  long drainRetryableOutcomes(StatusCode status) {
    long total = 0;
    for (TpccTransactionType type : TRANSACTION_TYPES) {
      total = add(total, drainRetryableOutcomes(type, status));
    }
    return total;
  }

  long drainRetryableOutcomes() {
    long total = 0;
    for (StatusCode status : STATUS_VALUES) {
      total = add(total, drainRetryableOutcomes(status));
    }
    return total;
  }

  long drainRetryableOutcomes(TpccTransactionType type, StatusCode status) {
    return drainRetryableOutcomes[type.ordinal()][status.ordinal()];
  }

  long drainClientRetries(StatusCode status) {
    long total = 0;
    for (TpccTransactionType type : TRANSACTION_TYPES) {
      total = add(total, drainClientRetries(type, status));
    }
    return total;
  }

  long drainClientRetries(TpccTransactionType type, StatusCode status) {
    return drainClientRetries[type.ordinal()][status.ordinal()];
  }

  long transactionAttempts() { return sum(attemptsStarted); }
  long transactionAttempts(TpccTransactionType type) { return attemptsStarted[type.ordinal()]; }
  long drainTransactionAttempts() { return sum(drainAttemptsStarted); }
  long firstAttemptId() { return attemptObserved ? firstAttemptId : 0; }
  long lastAttemptId() { return lastAttemptId; }
  long unclassifiedRetryFailures() { return unclassifiedRetryFailures; }
  long drainUnclassifiedRetryFailures() { return drainUnclassifiedRetryFailures; }
  long retryCorrelationCount() { return retryCorrelationCount; }
  long retryCorrelationOverflows() { return retryCorrelationOverflows; }
  boolean overflowed() { return overflowed; }

  private void recordRetryCorrelation(
      TpccTransactionType type,
      StatusCode status,
      boolean clientWillRetry,
      boolean measured,
      long attemptTag,
      long logicalSequence,
      int terminal,
      int attemptNumber,
      long stepTag) {
    if (type == null || status == null || attemptTag <= 0 || logicalSequence <= 0
        || terminal < 0 || attemptNumber <= 0 || stepTag < 0) {
      throw new IllegalArgumentException("invalid retry correlation");
    }
    if (retryCorrelationCount == Long.MAX_VALUE) {
      retryCorrelationOverflows = increment(retryCorrelationOverflows);
      overflowed = true;
    } else retryCorrelationCount = increment(retryCorrelationCount);
    System.out.println("retry_correlation index=" + attemptTag
        + " attempt_tag=" + attemptTag
        + " logical_sequence=" + logicalSequence
        + " terminal=" + terminal
        + " transaction=" + type
        + " attempt=" + attemptNumber
        + " step_tag=" + stepTag
        + " status=" + status
        + " client_will_retry=" + clientWillRetry
        + " measured=" + measured);
  }

  private void addCounts(long[] target, long[] source) {
    for (int index = 0; index < target.length; index++) {
      target[index] = add(target[index], source[index]);
    }
  }

  private void addCounts(long[][] target, long[][] source) {
    for (int index = 0; index < target.length; index++) {
      addCounts(target[index], source[index]);
    }
  }

  private long sum(long[] values) {
    long total = 0;
    for (long value : values) total = add(total, value);
    return total;
  }

  private long sum(long[][] values) {
    long total = 0;
    for (long[] row : values) total = add(total, sum(row));
    return total;
  }

  private long increment(long value) {
    if (value == Long.MAX_VALUE) {
      overflowed = true;
      return value;
    }
    return value + 1;
  }

  private long add(long current, long value) {
    if (current < 0 || value < 0 || current > Long.MAX_VALUE - value) {
      overflowed = true;
      return Long.MAX_VALUE;
    }
    return current + value;
  }
}
