package io.riverdb.bench.tpcc;

/** Owns bounded transaction latency histograms and latency maxima. */
final class TpccLatencyMetrics {
  private static final int BUCKETS = 64;
  private static final int TYPE_COUNT = TpccTransactionType.values().length;
  private final long[][] histogram = new long[TYPE_COUNT][BUCKETS];
  private final long[] maximumLatencyNanosByType =
      new long[TYPE_COUNT];
  private long maximumLatencyNanos;
  private boolean overflowed;

  void record(TpccTransactionType type, long nanos) {
    int index = type.ordinal();
    int bucket = bucket(nanos);
    histogram[index][bucket] = increment(histogram[index][bucket]);
    if (nanos > maximumLatencyNanosByType[index]) {
      maximumLatencyNanosByType[index] = nanos;
    }
    if (nanos > maximumLatencyNanos) maximumLatencyNanos = nanos;
  }

  void add(TpccLatencyMetrics source) {
    for (int type = 0; type < histogram.length; type++) {
      maximumLatencyNanosByType[type] = Math.max(
          maximumLatencyNanosByType[type], source.maximumLatencyNanosByType[type]);
      for (int bucket = 0; bucket < BUCKETS; bucket++) {
        histogram[type][bucket] = add(
            histogram[type][bucket], source.histogram[type][bucket]);
      }
    }
    maximumLatencyNanos = Math.max(maximumLatencyNanos, source.maximumLatencyNanos);
    overflowed |= source.overflowed;
  }

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

  boolean overflowed() { return overflowed; }

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

  private static int bucket(long nanos) {
    if (nanos <= 1) return 0;
    return 64 - Long.numberOfLeadingZeros(nanos - 1);
  }

  private static long percentileRank(long count, int permille) {
    long whole = count / 1_000;
    long remainder = count % 1_000;
    long tail = (remainder * permille + 999) / 1_000;
    return Math.max(1, whole * permille + tail);
  }
}
