package io.riverdb.bench.tpcc;

/** Fixed-size per-terminal latency histogram and outcome counters. */
final class TpccMetrics {
  private static final TpccTransactionType[] TRANSACTION_TYPES = TpccTransactionType.values();
  static final int TYPE_COUNT = TRANSACTION_TYPES.length;
  private final TpccLatencyMetrics latency = new TpccLatencyMetrics();
  private final TpccProtocolMetrics protocol = new TpccProtocolMetrics();
  private final TpccRetryMetrics retry = new TpccRetryMetrics();
  private final long[] committed = new long[TYPE_COUNT];
  private final long[] expectedRollbacks = new long[TYPE_COUNT];
  private final long[] retryExhausted = new long[TYPE_COUNT];
  private final long[] failed = new long[TYPE_COUNT];
  private final long[] drainCommitted = new long[TYPE_COUNT];
  private final long[] drainExpectedRollbacks = new long[TYPE_COUNT];
  private final long[] drainRetryExhausted = new long[TYPE_COUNT];
  private final long[] drainFailed = new long[TYPE_COUNT];
  private final long[] newOrderProgramFailures =
      new long[TpccRiverNewOrder.FAILURE_KINDS];
  private long allocatedBytes;
  private boolean allocationObserved;
  private final long[] started = new long[TYPE_COUNT];
  private long inFlightAtCutoff;
  private boolean overflowed;

  void markStarted(TpccTransactionType type) {
    int index = type.ordinal();
    started[index] = increment(started[index]);
  }

  void record(TpccTransactionType type, long nanos, TpccRetry.Result result) {
    int index = type.ordinal();
    latency.record(type, nanos);
    if (result.committed()) committed[index] = increment(committed[index]);
    else if (result.retryExhausted()) retryExhausted[index] = increment(retryExhausted[index]);
    else expectedRollbacks[index] = increment(expectedRollbacks[index]);
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
    latency.record(type, nanos);
    failed[index] = increment(failed[index]);
  }

  void add(TpccMetrics source) {
    addCounts(committed, source.committed);
    addCounts(expectedRollbacks, source.expectedRollbacks);
    addCounts(retryExhausted, source.retryExhausted);
    addCounts(failed, source.failed);
    addCounts(drainCommitted, source.drainCommitted);
    addCounts(drainExpectedRollbacks, source.drainExpectedRollbacks);
    addCounts(drainRetryExhausted, source.drainRetryExhausted);
    addCounts(drainFailed, source.drainFailed);
    addCounts(started, source.started);
    protocol.add(source.protocol);
    latency.add(source.latency);
    retry.add(source.retry);
    addCounts(newOrderProgramFailures, source.newOrderProgramFailures);
    allocatedBytes = add(allocatedBytes, source.allocatedBytes);
    allocationObserved |= source.allocationObserved;
    inFlightAtCutoff = add(inFlightAtCutoff, source.inFlightAtCutoff);
    overflowed |= source.overflowed();
  }

  TpccLatencyMetrics latency() { return latency; }

  TpccProtocolMetrics protocol() { return protocol; }

  TpccRetryMetrics retry() { return retry; }

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
    return sum(started);
  }

  void inFlightAtCutoff(long count) {
    if (count < 0) throw new IllegalArgumentException("in-flight count must not be negative");
    inFlightAtCutoff = add(inFlightAtCutoff, count);
  }

  long inFlightAtCutoff() { return inFlightAtCutoff; }

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

  long totalCommitted() {
    return sum(committed);
  }

  long total() {
    long total = 0;
    for (TpccTransactionType type : TRANSACTION_TYPES) {
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
    for (TpccTransactionType type : TRANSACTION_TYPES) {
      total = add(total, drainTotal(type));
    }
    return total;
  }

  long drainTotal(TpccTransactionType type) {
    int index = type.ordinal();
    return add(add(drainCommitted[index], drainExpectedRollbacks[index]),
        add(drainRetryExhausted[index], drainFailed[index]));
  }

  boolean overflowed() {
    return overflowed || latency.overflowed() || protocol.overflowed() || retry.overflowed();
  }

  private void addCounts(long[] target, long[] source) {
    for (int index = 0; index < target.length; index++) {
      target[index] = add(target[index], source[index]);
    }
  }

  private long sum(long[] values) {
    long total = 0;
    for (long value : values) total = add(total, value);
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

  private long addSigned(long current, long value) {
    if (value > 0 && current > Long.MAX_VALUE - value
        || value < 0 && current < Long.MIN_VALUE - value) {
      overflowed = true;
      return value < 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
    }
    return current + value;
  }

}
