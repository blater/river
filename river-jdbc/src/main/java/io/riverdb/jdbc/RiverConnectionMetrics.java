package io.riverdb.jdbc;

/** Allocation-free transport counters exposed through {@link java.sql.Wrapper#unwrap}. */
public interface RiverConnectionMetrics {
  long completedRequests();

  long bytesSent();

  long bytesReceived();
}
