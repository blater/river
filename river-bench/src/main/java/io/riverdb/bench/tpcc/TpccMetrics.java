package io.riverdb.bench.tpcc;

import io.riverdb.base.error.StatusCode;

/** Fixed-size per-terminal latency histogram and outcome counters. */
final class TpccMetrics {
  private static final int BUCKETS = 64;
  private static final int STATUSES = StatusCode.values().length;
  private final long[][] histogram = new long[TpccTransactionType.values().length][BUCKETS];
  private final long[] committed = new long[TpccTransactionType.values().length];
  private final long[] expectedRollbacks = new long[TpccTransactionType.values().length];
  private final long[] retryExhausted = new long[TpccTransactionType.values().length];
  private final long[] failed = new long[TpccTransactionType.values().length];
  private final long[] drainCommitted = new long[TpccTransactionType.values().length];
  private final long[] drainExpectedRollbacks = new long[TpccTransactionType.values().length];
  private final long[] drainRetryExhausted = new long[TpccTransactionType.values().length];
  private final long[] drainFailed = new long[TpccTransactionType.values().length];
  private final long[] attemptsStarted = new long[TpccTransactionType.values().length];
  private final long[] drainAttemptsStarted = new long[TpccTransactionType.values().length];
  private final long[][] retryableOutcomes =
      new long[TpccTransactionType.values().length][STATUSES];
  private final long[][] clientRetries =
      new long[TpccTransactionType.values().length][STATUSES];
  private final long[][] drainRetryableOutcomes =
      new long[TpccTransactionType.values().length][STATUSES];
  private final long[][] drainClientRetries =
      new long[TpccTransactionType.values().length][STATUSES];
  private final long[] protocolRequestsByType =
      new long[TpccTransactionType.values().length];
  private final long[] protocolBytesSentByType =
      new long[TpccTransactionType.values().length];
  private final long[] protocolBytesReceivedByType =
      new long[TpccTransactionType.values().length];
  private final long[] drainProtocolRequestsByType =
      new long[TpccTransactionType.values().length];
  private final long[] drainProtocolBytesSentByType =
      new long[TpccTransactionType.values().length];
  private final long[] drainProtocolBytesReceivedByType =
      new long[TpccTransactionType.values().length];
  private final long[] maximumLatencyNanosByType =
      new long[TpccTransactionType.values().length];
  private final long[] newOrderProgramFailures =
      new long[TpccRiverNewOrder.FAILURE_KINDS];
  private long protocolRequests;
  private long protocolBytesSent;
  private long protocolBytesReceived;
  private long drainProtocolRequests;
  private long drainProtocolBytesSent;
  private long drainProtocolBytesReceived;
  private long allocatedBytes;
  private boolean allocationObserved;
  private final long[] started = new long[TpccTransactionType.values().length];
  private long inFlightAtCutoff;
  private long maximumLatencyNanos;
  private long firstAttemptId = Long.MAX_VALUE;
  private long lastAttemptId;
  private boolean attemptObserved;
  private long unclassifiedRetryFailures;
  private long drainUnclassifiedRetryFailures;
  private long retryCorrelationOverflows;
  private long retryCorrelationCount;
  private boolean overflowed;

  void markStarted(TpccTransactionType type) {
    int index = type.ordinal();
    started[index] = increment(started[index]);
  }

  void attemptStarted(TpccTransactionType type, long attemptId, boolean measured) {
    if (attemptId <= 0) throw new IllegalArgumentException("attempt ID must be positive");
    if (measured) {
      int index = type.ordinal();
      attemptsStarted[index] = increment(attemptsStarted[index]);
      firstAttemptId = attemptObserved ? Math.min(firstAttemptId, attemptId) : attemptId;
      lastAttemptId = Math.max(lastAttemptId, attemptId);
      attemptObserved = true;
    } else {
      int index = type.ordinal();
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

  void record(TpccTransactionType type, long nanos, TpccRetry.Result result) {
    int index = type.ordinal();
    int bucket = bucket(nanos);
    histogram[index][bucket] = increment(histogram[index][bucket]);
    if (result.committed()) committed[index] = increment(committed[index]);
    else if (result.retryExhausted()) retryExhausted[index] = increment(retryExhausted[index]);
    else expectedRollbacks[index] = increment(expectedRollbacks[index]);
    if (nanos > maximumLatencyNanosByType[index]) maximumLatencyNanosByType[index] = nanos;
    if (nanos > maximumLatencyNanos) maximumLatencyNanos = nanos;
  }

  void recordDrain(TpccTransactionType type, TpccRetry.Result result) {
    int index = type.ordinal();
    if (result.committed()) drainCommitted[index] = increment(drainCommitted[index]);
    else if (result.retryExhausted()) {
      drainRetryExhausted[index] = increment(drainRetryExhausted[index]);
    } else {
      drainExpectedRollbacks[index] = increment(drainExpectedRollbacks[index]);
    }
  }

  void failure(TpccTransactionType type, long nanos, boolean measured) {
    if (!measured) {
      int index = type.ordinal();
      drainFailed[index] = increment(drainFailed[index]);
      return;
    }
    int index = type.ordinal();
    int bucket = bucket(nanos);
    histogram[index][bucket] = increment(histogram[index][bucket]);
    failed[index] = increment(failed[index]);
    if (nanos > maximumLatencyNanosByType[index]) {
      maximumLatencyNanosByType[index] = nanos;
    }
    if (nanos > maximumLatencyNanos) maximumLatencyNanos = nanos;
  }

  void add(TpccMetrics source) {
    for (int type = 0; type < committed.length; type++) {
      committed[type] = add(committed[type], source.committed[type]);
      expectedRollbacks[type] = add(expectedRollbacks[type], source.expectedRollbacks[type]);
      retryExhausted[type] = add(retryExhausted[type], source.retryExhausted[type]);
      failed[type] = add(failed[type], source.failed[type]);
      drainCommitted[type] = add(drainCommitted[type], source.drainCommitted[type]);
      drainExpectedRollbacks[type] = add(
          drainExpectedRollbacks[type], source.drainExpectedRollbacks[type]);
      drainRetryExhausted[type] = add(
          drainRetryExhausted[type], source.drainRetryExhausted[type]);
      drainFailed[type] = add(drainFailed[type], source.drainFailed[type]);
      started[type] = add(started[type], source.started[type]);
      protocolRequestsByType[type] = add(
          protocolRequestsByType[type], source.protocolRequestsByType[type]);
      protocolBytesSentByType[type] = add(
          protocolBytesSentByType[type], source.protocolBytesSentByType[type]);
      protocolBytesReceivedByType[type] = add(
          protocolBytesReceivedByType[type], source.protocolBytesReceivedByType[type]);
      drainProtocolRequestsByType[type] = add(
          drainProtocolRequestsByType[type], source.drainProtocolRequestsByType[type]);
      drainProtocolBytesSentByType[type] = add(
          drainProtocolBytesSentByType[type], source.drainProtocolBytesSentByType[type]);
      drainProtocolBytesReceivedByType[type] = add(
          drainProtocolBytesReceivedByType[type], source.drainProtocolBytesReceivedByType[type]);
      maximumLatencyNanosByType[type] = Math.max(
          maximumLatencyNanosByType[type], source.maximumLatencyNanosByType[type]);
      for (int bucket = 0; bucket < BUCKETS; bucket++) {
        histogram[type][bucket] = add(
            histogram[type][bucket], source.histogram[type][bucket]);
      }
    }
    for (int type = 0; type < attemptsStarted.length; type++) {
      attemptsStarted[type] = add(attemptsStarted[type], source.attemptsStarted[type]);
      drainAttemptsStarted[type] = add(
          drainAttemptsStarted[type], source.drainAttemptsStarted[type]);
      for (int status = 0; status < STATUSES; status++) {
        retryableOutcomes[type][status] = add(
            retryableOutcomes[type][status], source.retryableOutcomes[type][status]);
        clientRetries[type][status] = add(
            clientRetries[type][status], source.clientRetries[type][status]);
        drainRetryableOutcomes[type][status] = add(
            drainRetryableOutcomes[type][status],
            source.drainRetryableOutcomes[type][status]);
        drainClientRetries[type][status] = add(
            drainClientRetries[type][status], source.drainClientRetries[type][status]);
      }
    }
    for (int kind = 0; kind < newOrderProgramFailures.length; kind++) {
      newOrderProgramFailures[kind] = add(
          newOrderProgramFailures[kind], source.newOrderProgramFailures[kind]);
    }
    protocolRequests = add(protocolRequests, source.protocolRequests);
    protocolBytesSent = add(protocolBytesSent, source.protocolBytesSent);
    protocolBytesReceived = add(protocolBytesReceived, source.protocolBytesReceived);
    drainProtocolRequests = add(drainProtocolRequests, source.drainProtocolRequests);
    drainProtocolBytesSent = add(drainProtocolBytesSent, source.drainProtocolBytesSent);
    drainProtocolBytesReceived = add(
        drainProtocolBytesReceived, source.drainProtocolBytesReceived);
    allocatedBytes = add(allocatedBytes, source.allocatedBytes);
    allocationObserved |= source.allocationObserved;
    inFlightAtCutoff = add(inFlightAtCutoff, source.inFlightAtCutoff);
    maximumLatencyNanos = Math.max(maximumLatencyNanos, source.maximumLatencyNanos);
    if (source.attemptObserved) {
      firstAttemptId = attemptObserved
          ? Math.min(firstAttemptId, source.firstAttemptId) : source.firstAttemptId;
      lastAttemptId = Math.max(lastAttemptId, source.lastAttemptId);
      attemptObserved = true;
    }
    unclassifiedRetryFailures = add(
        unclassifiedRetryFailures, source.unclassifiedRetryFailures);
    drainUnclassifiedRetryFailures = add(
        drainUnclassifiedRetryFailures, source.drainUnclassifiedRetryFailures);
    retryCorrelationCount = add(retryCorrelationCount, source.retryCorrelationCount);
    retryCorrelationOverflows = add(
        retryCorrelationOverflows, source.retryCorrelationOverflows);
    overflowed |= source.overflowed;
  }

  long committed(TpccTransactionType type) {
    return committed[type.ordinal()];
  }

  long expectedRollbacks(TpccTransactionType type) {
    return expectedRollbacks[type.ordinal()];
  }

  long retryExhausted(TpccTransactionType type) {
    return retryExhausted[type.ordinal()];
  }

  long failed(TpccTransactionType type) {
    return failed[type.ordinal()];
  }

  long drainCommitted(TpccTransactionType type) { return drainCommitted[type.ordinal()]; }

  long drainExpectedRollbacks(TpccTransactionType type) {
    return drainExpectedRollbacks[type.ordinal()];
  }

  long drainRetryExhausted(TpccTransactionType type) {
    return drainRetryExhausted[type.ordinal()];
  }

  long drainFailed(TpccTransactionType type) { return drainFailed[type.ordinal()]; }

  long started(TpccTransactionType type) { return started[type.ordinal()]; }

  long started() {
    long total = 0;
    for (long count : started) total = add(total, count);
    return total;
  }

  void inFlightAtCutoff(long count) {
    if (count < 0) throw new IllegalArgumentException("in-flight count must not be negative");
    inFlightAtCutoff = add(inFlightAtCutoff, count);
  }

  long inFlightAtCutoff() { return inFlightAtCutoff; }

  long maximumLatencyMicros() { return maximumLatencyNanos / 1_000L; }

  long maximumLatencyMicros(TpccTransactionType type) {
    return maximumLatencyNanosByType[type.ordinal()] / 1_000L;
  }

  long histogram(TpccTransactionType type, int bucket) {
    if (bucket < 0 || bucket >= BUCKETS) {
      throw new IllegalArgumentException("histogram bucket outside bound");
    }
    return histogram[type.ordinal()][bucket];
  }

  long retries() {
    long total = 0;
    for (TpccTransactionType type : TpccTransactionType.values()) {
      total = add(total, retries(type));
    }
    return total;
  }

  long retries(TpccTransactionType type) {
    long total = 0;
    for (long count : clientRetries[type.ordinal()]) total = add(total, count);
    return total;
  }

  long retryableOutcomes(StatusCode status) {
    long total = 0;
    for (TpccTransactionType type : TpccTransactionType.values()) {
      total = add(total, retryableOutcomes(type, status));
    }
    return total;
  }

  long retryableOutcomes() {
    long total = 0;
    for (StatusCode status : StatusCode.values()) {
      total = add(total, retryableOutcomes(status));
    }
    return total;
  }

  long retryableOutcomes(TpccTransactionType type, StatusCode status) {
    return retryableOutcomes[type.ordinal()][status.ordinal()];
  }

  long clientRetries(StatusCode status) {
    long total = 0;
    for (TpccTransactionType type : TpccTransactionType.values()) {
      total = add(total, clientRetries[type.ordinal()][status.ordinal()]);
    }
    return total;
  }

  long clientRetries(TpccTransactionType type, StatusCode status) {
    return clientRetries[type.ordinal()][status.ordinal()];
  }

  long drainRetryableOutcomes(StatusCode status) {
    long total = 0;
    for (TpccTransactionType type : TpccTransactionType.values()) {
      total = add(total, drainRetryableOutcomes[type.ordinal()][status.ordinal()]);
    }
    return total;
  }

  long drainRetryableOutcomes() {
    long total = 0;
    for (StatusCode status : StatusCode.values()) {
      total = add(total, drainRetryableOutcomes(status));
    }
    return total;
  }

  long drainRetryableOutcomes(TpccTransactionType type, StatusCode status) {
    return drainRetryableOutcomes[type.ordinal()][status.ordinal()];
  }

  long drainClientRetries(StatusCode status) {
    long total = 0;
    for (TpccTransactionType type : TpccTransactionType.values()) {
      total = add(total, drainClientRetries[type.ordinal()][status.ordinal()]);
    }
    return total;
  }

  long drainClientRetries(TpccTransactionType type, StatusCode status) {
    return drainClientRetries[type.ordinal()][status.ordinal()];
  }

  void beginProgramFailures(TpccRiverNewOrder transaction) {
    for (int kind = 0; kind < newOrderProgramFailures.length; kind++) {
      newOrderProgramFailures[kind] = -transaction.failureCount(kind);
    }
  }

  void completeProgramFailures(TpccRiverNewOrder transaction) {
    for (int kind = 0; kind < newOrderProgramFailures.length; kind++) {
      newOrderProgramFailures[kind] = addSigned(
          newOrderProgramFailures[kind], transaction.failureCount(kind));
    }
  }

  long newOrderProgramFailures(int kind) {
    return kind >= 0 && kind < newOrderProgramFailures.length
        ? newOrderProgramFailures[kind] : 0;
  }

  void protocol(TpccTransactionType type, long requests, long sent, long received) {
    if (requests < 0 || sent < 0 || received < 0) {
      throw new IllegalArgumentException("protocol counters must be monotonic");
    }
    int index = type.ordinal();
    protocolRequestsByType[index] = add(protocolRequestsByType[index], requests);
    protocolBytesSentByType[index] = add(protocolBytesSentByType[index], sent);
    protocolBytesReceivedByType[index] = add(protocolBytesReceivedByType[index], received);
    protocolRequests = add(protocolRequests, requests);
    protocolBytesSent = add(protocolBytesSent, sent);
    protocolBytesReceived = add(protocolBytesReceived, received);
  }

  void drainProtocol(TpccTransactionType type, long requests, long sent, long received) {
    if (requests < 0 || sent < 0 || received < 0) {
      throw new IllegalArgumentException("protocol counters must be monotonic");
    }
    int index = type.ordinal();
    drainProtocolRequestsByType[index] = add(drainProtocolRequestsByType[index], requests);
    drainProtocolBytesSentByType[index] = add(drainProtocolBytesSentByType[index], sent);
    drainProtocolBytesReceivedByType[index] = add(
        drainProtocolBytesReceivedByType[index], received);
    drainProtocolRequests = add(drainProtocolRequests, requests);
    drainProtocolBytesSent = add(drainProtocolBytesSent, sent);
    drainProtocolBytesReceived = add(drainProtocolBytesReceived, received);
  }

  long protocolRequests() { return protocolRequests; }

  long protocolBytesSent() { return protocolBytesSent; }

  long protocolBytesReceived() { return protocolBytesReceived; }

  long drainProtocolRequests() { return drainProtocolRequests; }

  long drainProtocolBytesSent() { return drainProtocolBytesSent; }

  long drainProtocolBytesReceived() { return drainProtocolBytesReceived; }

  long protocolRequests(TpccTransactionType type) {
    return protocolRequestsByType[type.ordinal()];
  }

  long protocolBytesSent(TpccTransactionType type) {
    return protocolBytesSentByType[type.ordinal()];
  }

  long protocolBytesReceived(TpccTransactionType type) {
    return protocolBytesReceivedByType[type.ordinal()];
  }

  long drainProtocolRequests(TpccTransactionType type) {
    return drainProtocolRequestsByType[type.ordinal()];
  }

  long drainProtocolBytesSent(TpccTransactionType type) {
    return drainProtocolBytesSentByType[type.ordinal()];
  }

  long drainProtocolBytesReceived(TpccTransactionType type) {
    return drainProtocolBytesReceivedByType[type.ordinal()];
  }

  double protocolRequestsPerAttempt(TpccTransactionType type) {
    long attempts = transactionAttempts(type);
    return attempts == 0 ? 0.0 : protocolRequests(type) / (double) attempts;
  }

  void allocated(long bytes) {
    if (bytes >= 0) {
      allocatedBytes = add(allocatedBytes, bytes);
      allocationObserved = true;
    }
  }

  long allocatedBytes() {
    return allocatedBytes;
  }

  boolean allocationObserved() {
    return allocationObserved;
  }

  long percentileMicros(TpccTransactionType type, int percentage) {
    return percentileMicrosPermille(type, percentage * 10);
  }

  long percentileMicrosPermille(TpccTransactionType type, int permille) {
    if (permille < 1 || permille > 1_000) {
      throw new IllegalArgumentException("percentile must be between 0.1 and 100");
    }
    long count = 0;
    for (long value : histogram[type.ordinal()]) count = add(count, value);
    if (count == 0) return 0;
    long target = percentileRank(count, permille);
    long seen = 0;
    for (int bucket = 0; bucket < BUCKETS; bucket++) {
      seen = add(seen, histogram[type.ordinal()][bucket]);
      if (seen >= target) return (1L << Math.min(bucket, 62)) / 1_000L;
    }
    return Long.MAX_VALUE / 1_000L;
  }

  long totalCommitted() {
    long total = 0;
    for (long count : committed) total = add(total, count);
    return total;
  }

  long total() {
    long total = 0;
    for (TpccTransactionType type : TpccTransactionType.values()) {
      total = add(total, total(type));
    }
    return total;
  }

  long total(TpccTransactionType type) {
    int index = type.ordinal();
    return add(add(committed[index], expectedRollbacks[index]),
        add(retryExhausted[index], failed[index]));
  }

  long drainTotal() {
    long total = 0;
    for (TpccTransactionType type : TpccTransactionType.values()) {
      total = add(total, drainTotal(type));
    }
    return total;
  }

  long drainTotal(TpccTransactionType type) {
    int index = type.ordinal();
    return add(add(drainCommitted[index], drainExpectedRollbacks[index]),
        add(drainRetryExhausted[index], drainFailed[index]));
  }

  long transactionAttempts() {
    long total = 0;
    for (long count : attemptsStarted) total = add(total, count);
    return total;
  }

  long transactionAttempts(TpccTransactionType type) {
    return attemptsStarted[type.ordinal()];
  }

  long drainTransactionAttempts() {
    long total = 0;
    for (long count : drainAttemptsStarted) total = add(total, count);
    return total;
  }

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

  private static int bucket(long nanos) {
    if (nanos <= 1) return 0;
    return 64 - Long.numberOfLeadingZeros(nanos - 1);
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

  private long addSigned(long current, long value) {
    if (value > 0 && current > Long.MAX_VALUE - value
        || value < 0 && current < Long.MIN_VALUE - value) {
      overflowed = true;
      return value < 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
    }
    return current + value;
  }

  private static long percentileRank(long count, int permille) {
    long whole = count / 1_000;
    long remainder = count % 1_000;
    long tail = (remainder * permille + 999) / 1_000;
    return Math.max(1, whole * permille + tail);
  }
}
