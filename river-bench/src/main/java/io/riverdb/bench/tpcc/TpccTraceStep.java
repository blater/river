package io.riverdb.bench.tpcc;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;

/** One measured JDBC operation in the single-update trace. */
@Name("io.riverdb.bench.tpcc.JdbcTraceStep")
@Label("JDBC trace step")
@Category({"River", "JDBC"})
final class TpccTraceStep extends Event {
  @Label("Step")
  String step;

  @Label("Outcome")
  String outcome;

  @Label("Wall nanoseconds")
  long wallNanos;

  @Label("CPU nanoseconds")
  long cpuNanos;

  @Label("Non-CPU nanoseconds")
  long nonCpuNanos;

  @Label("Allocated bytes")
  long allocatedBytes;

  @Label("Protocol requests")
  long protocolRequests;

  @Label("Bytes sent")
  long bytesSent;

  @Label("Bytes received")
  long bytesReceived;
}
