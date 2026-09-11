package io.riverdb.bench.tpcc;

/** Owns measured and drain protocol request and byte accounting. */
final class TpccProtocolMetrics {
  private static final int TYPE_COUNT = TpccTransactionType.values().length;
  private final long[] requestsByType = new long[TYPE_COUNT];
  private final long[] bytesSentByType = new long[TYPE_COUNT];
  private final long[] bytesReceivedByType = new long[TYPE_COUNT];
  private final long[] drainRequestsByType = new long[TYPE_COUNT];
  private final long[] drainBytesSentByType = new long[TYPE_COUNT];
  private final long[] drainBytesReceivedByType = new long[TYPE_COUNT];
  private long requests;
  private long bytesSent;
  private long bytesReceived;
  private long drainRequests;
  private long drainBytesSent;
  private long drainBytesReceived;
  private boolean overflowed;

  void record(
      TpccTransactionType type, long requestCount, long sent, long received) {
    validate(requestCount, sent, received);
    int index = type.ordinal();
    requestsByType[index] = add(requestsByType[index], requestCount);
    bytesSentByType[index] = add(bytesSentByType[index], sent);
    bytesReceivedByType[index] = add(bytesReceivedByType[index], received);
    requests = add(requests, requestCount);
    bytesSent = add(bytesSent, sent);
    bytesReceived = add(bytesReceived, received);
  }

  void recordDrain(
      TpccTransactionType type, long requestCount, long sent, long received) {
    validate(requestCount, sent, received);
    int index = type.ordinal();
    drainRequestsByType[index] = add(drainRequestsByType[index], requestCount);
    drainBytesSentByType[index] = add(drainBytesSentByType[index], sent);
    drainBytesReceivedByType[index] = add(drainBytesReceivedByType[index], received);
    drainRequests = add(drainRequests, requestCount);
    drainBytesSent = add(drainBytesSent, sent);
    drainBytesReceived = add(drainBytesReceived, received);
  }

  void add(TpccProtocolMetrics source) {
    for (int type = 0; type < TYPE_COUNT; type++) {
      requestsByType[type] = add(requestsByType[type], source.requestsByType[type]);
      bytesSentByType[type] = add(bytesSentByType[type], source.bytesSentByType[type]);
      bytesReceivedByType[type] = add(
          bytesReceivedByType[type], source.bytesReceivedByType[type]);
      drainRequestsByType[type] = add(
          drainRequestsByType[type], source.drainRequestsByType[type]);
      drainBytesSentByType[type] = add(
          drainBytesSentByType[type], source.drainBytesSentByType[type]);
      drainBytesReceivedByType[type] = add(
          drainBytesReceivedByType[type], source.drainBytesReceivedByType[type]);
    }
    requests = add(requests, source.requests);
    bytesSent = add(bytesSent, source.bytesSent);
    bytesReceived = add(bytesReceived, source.bytesReceived);
    drainRequests = add(drainRequests, source.drainRequests);
    drainBytesSent = add(drainBytesSent, source.drainBytesSent);
    drainBytesReceived = add(drainBytesReceived, source.drainBytesReceived);
    overflowed |= source.overflowed;
  }

  long requests() { return requests; }
  long bytesSent() { return bytesSent; }
  long bytesReceived() { return bytesReceived; }
  long drainRequests() { return drainRequests; }
  long drainBytesSent() { return drainBytesSent; }
  long drainBytesReceived() { return drainBytesReceived; }

  long requests(TpccTransactionType type) { return requestsByType[type.ordinal()]; }
  long bytesSent(TpccTransactionType type) { return bytesSentByType[type.ordinal()]; }
  long bytesReceived(TpccTransactionType type) {
    return bytesReceivedByType[type.ordinal()];
  }
  long drainRequests(TpccTransactionType type) {
    return drainRequestsByType[type.ordinal()];
  }
  long drainBytesSent(TpccTransactionType type) {
    return drainBytesSentByType[type.ordinal()];
  }
  long drainBytesReceived(TpccTransactionType type) {
    return drainBytesReceivedByType[type.ordinal()];
  }

  double requestsPerAttempt(TpccTransactionType type, long attempts) {
    return attempts == 0 ? 0.0 : requests(type) / (double) attempts;
  }

  boolean overflowed() { return overflowed; }

  private static void validate(long requests, long sent, long received) {
    if (requests < 0 || sent < 0 || received < 0) {
      throw new IllegalArgumentException("protocol counters must be monotonic");
    }
  }

  private long add(long current, long value) {
    if (current < 0 || value < 0 || current > Long.MAX_VALUE - value) {
      overflowed = true;
      return Long.MAX_VALUE;
    }
    return current + value;
  }
}
