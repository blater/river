package io.riverdb.bench.tpcc;

/** Fixed-size per-terminal latency histogram and outcome counters. */
final class TpccMetrics {
  private static final int BUCKETS = 64;
  private final long[][] histogram = new long[TpccTransactionType.values().length][BUCKETS];
  private final long[] committed = new long[TpccTransactionType.values().length];
  private final long[] expectedRollbacks = new long[TpccTransactionType.values().length];
  private final long[] retryExhausted = new long[TpccTransactionType.values().length];
  private final long[] failed = new long[TpccTransactionType.values().length];
  private final long[] retries = new long[TpccTransactionType.values().length];
  private final long[] protocolRequestsByType =
      new long[TpccTransactionType.values().length];
  private final long[] protocolBytesSentByType =
      new long[TpccTransactionType.values().length];
  private final long[] protocolBytesReceivedByType =
      new long[TpccTransactionType.values().length];
  private final long[] newOrderProgramFailures =
      new long[TpccRiverNewOrder.FAILURE_KINDS];
  private long protocolRequests;
  private long protocolBytesSent;
  private long protocolBytesReceived;
  private long allocatedBytes;
  private boolean allocationObserved;

  void record(TpccTransactionType type, long nanos, TpccRetry.Result result) {
    int index = type.ordinal();
    histogram[index][bucket(nanos)]++;
    if (result.committed()) committed[index]++;
    else if (result.retryExhausted()) retryExhausted[index]++;
    else expectedRollbacks[index]++;
    retries[index] += result.retries();
  }

  void failure(TpccTransactionType type, long nanos) {
    histogram[type.ordinal()][bucket(nanos)]++;
    failed[type.ordinal()]++;
  }

  void add(TpccMetrics source) {
    for (int type = 0; type < committed.length; type++) {
      committed[type] += source.committed[type];
      expectedRollbacks[type] += source.expectedRollbacks[type];
      retryExhausted[type] += source.retryExhausted[type];
      failed[type] += source.failed[type];
      protocolRequestsByType[type] += source.protocolRequestsByType[type];
      protocolBytesSentByType[type] += source.protocolBytesSentByType[type];
      protocolBytesReceivedByType[type] += source.protocolBytesReceivedByType[type];
      for (int bucket = 0; bucket < BUCKETS; bucket++) {
        histogram[type][bucket] += source.histogram[type][bucket];
      }
    }
    for (int type = 0; type < retries.length; type++) retries[type] += source.retries[type];
    for (int kind = 0; kind < newOrderProgramFailures.length; kind++) {
      newOrderProgramFailures[kind] += source.newOrderProgramFailures[kind];
    }
    protocolRequests += source.protocolRequests;
    protocolBytesSent += source.protocolBytesSent;
    protocolBytesReceived += source.protocolBytesReceived;
    allocatedBytes += source.allocatedBytes;
    allocationObserved |= source.allocationObserved;
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

  long retries() {
    long total = 0;
    for (long count : retries) total += count;
    return total;
  }

  long retries(TpccTransactionType type) { return retries[type.ordinal()]; }

  void beginProgramFailures(TpccRiverNewOrder transaction) {
    for (int kind = 0; kind < newOrderProgramFailures.length; kind++) {
      newOrderProgramFailures[kind] = -transaction.failureCount(kind);
    }
  }

  void completeProgramFailures(TpccRiverNewOrder transaction) {
    for (int kind = 0; kind < newOrderProgramFailures.length; kind++) {
      newOrderProgramFailures[kind] += transaction.failureCount(kind);
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
    protocolRequestsByType[index] += requests;
    protocolBytesSentByType[index] += sent;
    protocolBytesReceivedByType[index] += received;
    protocolRequests += requests;
    protocolBytesSent += sent;
    protocolBytesReceived += received;
  }

  long protocolRequests() { return protocolRequests; }

  long protocolBytesSent() { return protocolBytesSent; }

  long protocolBytesReceived() { return protocolBytesReceived; }

  long protocolRequests(TpccTransactionType type) {
    return protocolRequestsByType[type.ordinal()];
  }

  long protocolBytesSent(TpccTransactionType type) {
    return protocolBytesSentByType[type.ordinal()];
  }

  long protocolBytesReceived(TpccTransactionType type) {
    return protocolBytesReceivedByType[type.ordinal()];
  }

  double protocolRequestsPerAttempt(TpccTransactionType type) {
    long attempts = total(type) + retries(type);
    return attempts == 0 ? 0.0 : protocolRequests(type) / (double) attempts;
  }

  void allocated(long bytes) {
    if (bytes >= 0) {
      allocatedBytes += bytes;
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
    long count = 0;
    for (long value : histogram[type.ordinal()]) count += value;
    if (count == 0) return 0;
    long target = Math.max(1, (count * percentage + 99) / 100);
    long seen = 0;
    for (int bucket = 0; bucket < BUCKETS; bucket++) {
      seen += histogram[type.ordinal()][bucket];
      if (seen >= target) return (1L << Math.min(bucket, 62)) / 1_000L;
    }
    return Long.MAX_VALUE / 1_000L;
  }

  long totalCommitted() {
    long total = 0;
    for (long count : committed) total += count;
    return total;
  }

  long total(TpccTransactionType type) {
    int index = type.ordinal();
    return committed[index] + expectedRollbacks[index] + retryExhausted[index] + failed[index];
  }

  long transactionAttempts() {
    long total = retries();
    for (TpccTransactionType type : TpccTransactionType.values()) total += total(type);
    return total;
  }

  private static int bucket(long nanos) {
    if (nanos <= 1) return 0;
    return 64 - Long.numberOfLeadingZeros(nanos - 1);
  }
}
