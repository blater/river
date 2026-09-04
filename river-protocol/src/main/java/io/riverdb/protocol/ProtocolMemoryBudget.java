package io.riverdb.protocol;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.engine.api.ParameterSet;
import io.riverdb.engine.api.RetainedMemoryLease;
import io.riverdb.engine.api.RowResult;

/** Server-wide bound whose independent leases exactly own retained workspace bytes. */
public final class ProtocolMemoryBudget {
  private final long maximumBytes;
  private long retainedBytes;

  public ProtocolMemoryBudget(long maximum) {
    if (maximum < 0) throw new IllegalArgumentException("maximum");
    maximumBytes = maximum;
  }

  /** One maximum-format connection may burst while all other slots retain their warm floor. */
  public static ProtocolMemoryBudget forServer(int connections) {
    long maximum = serverMaximumBytes(connections);
    if (maximum < 0) throw new IllegalArgumentException("connections");
    return new ProtocolMemoryBudget(maximum);
  }

  /** Whether the configured connection admission fits this protocol's long-valued accounting. */
  public static boolean supportsServerConnections(int connections) {
    return serverMaximumBytes(connections) >= 0;
  }

  private static long serverMaximumBytes(int connections) {
    if (connections <= 0) return -1;
    long sqlFloor = add(ProtocolUtf8Decoder.retainedFloorBytes(),
        multiply(ParameterSet.retainedFloorBytes(), 2));
    sqlFloor = add(sqlFloor, multiply(RowResult.retainedFloorBytes(), 3));
    long programFloor = multiply(ProtocolWorkspaceRetention.warmCeilingBytes(), 2);
    long workspaceFloor = add(sqlFloor, programFloor);
    long fixedPerConnection = multiply(ProtocolFrameCodec.MAXIMUM_FRAME_BYTES, 2);
    long base = multiply(connections, add(fixedPerConnection, workspaceFloor));
    long requestAssembly = add(ProtocolFrameCodec.HEADER_BYTES,
        ProtocolFrameCodec.MAXIMUM_LOGICAL_REQUEST_PAYLOAD_BYTES);
    long responseGrowth = ProtocolFrameCodec.MAXIMUM_RESPONSE_BYTES
        - (long) ProtocolFrameCodec.MAXIMUM_FRAME_BYTES;
    long maximumSqlWorkspace = add(
        ProtocolUtf8Decoder.maximumRetainedBytes(SqlShapeLimits.MAX_SQL_TEXT_BYTES),
        multiply(ParameterSet.maximumRetainedBytes(), 2));
    maximumSqlWorkspace = add(
        maximumSqlWorkspace, multiply(RowResult.maximumRetainedBytes(), 3));
    long sqlDelta = add(requestAssembly, responseGrowth);
    sqlDelta = add(sqlDelta, maximumSqlWorkspace - sqlFloor);
    long programRequestDelta = ProtocolWorkspaceRetention.maximumProgramRequestRetainedBytes()
        - ProtocolWorkspaceRetention.warmCeilingBytes();
    long prepareDelta = add(requestAssembly, programRequestDelta);
    long executeDelta = add(prepareDelta,
        ProtocolWorkspaceRetention.maximumProgramResultRetainedBytes()
            - ProtocolWorkspaceRetention.warmCeilingBytes());
    executeDelta = add(executeDelta, responseGrowth);
    long phaseDelta = Math.max(sqlDelta, Math.max(prepareDelta, executeDelta));
    long maximum = add(base, phaseDelta);
    return maximum;
  }

  private static long multiply(long left, long right) {
    return left < 0 || right < 0 || left != 0 && right > Long.MAX_VALUE / left
        ? -1 : left * right;
  }

  private static long add(long left, long right) {
    return left < 0 || right < 0 || left > Long.MAX_VALUE - right ? -1 : left + right;
  }

  public RetainedMemoryLease lease() {
    return new Lease();
  }

  public synchronized long retainedBytes() { return retainedBytes; }
  public long maximumBytes() { return maximumBytes; }

  private synchronized StatusCode resize(long previous, long next) {
    if (next < 0 || previous < 0 || previous > retainedBytes) {
      return StatusCode.INVARIANT_BROKEN;
    }
    long increase = next - previous;
    if (increase > 0 && retainedBytes > maximumBytes - increase) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    retainedBytes += increase;
    if (increase < 0) notifyAll();
    return StatusCode.OK;
  }

  private final class Lease implements RetainedMemoryLease {
    private long bytes;

    @Override
    public StatusCode resize(long next) {
      StatusCode status = ProtocolMemoryBudget.this.resize(bytes, next);
      if (status.isOk()) bytes = next;
      return status;
    }

    @Override
    public StatusCode awaitResize(long next) {
      synchronized (ProtocolMemoryBudget.this) {
        StatusCode status = ProtocolMemoryBudget.this.resize(bytes, next);
        while (status == StatusCode.RESOURCE_EXHAUSTED) {
          try {
            ProtocolMemoryBudget.this.wait();
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return StatusCode.CANCELLED;
          }
          status = ProtocolMemoryBudget.this.resize(bytes, next);
        }
        if (status.isOk()) bytes = next;
        return status;
      }
    }

    @Override
    public long retainedBytes() {
      return bytes;
    }
  }
}
