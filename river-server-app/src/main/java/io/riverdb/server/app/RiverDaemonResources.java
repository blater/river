package io.riverdb.server.app;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.runtime.DatabaseResourcePlan;
import io.riverdb.engine.runtime.DatabaseResourcePlanRequest;
import io.riverdb.format.page.PageCodec;
import io.riverdb.protocol.ProtocolMemoryBudget;

/**
 * Caller-owned development resource profile for the installed daemon.
 *
 * <p>The profile admits the database plan and protocol retention before identity or database
 * mutation.  Connection admission is bounded by the explicit protocol budget, rather than by a
 * convenience connection constant.  Before compiling the plan it also requires
 * {@code 256_000_000 + ProtocolMemoryBudget.forServer(connections).maximumBytes()
 * + 128_000_000 <= Runtime.maxMemory()}; the final term is the lifecycle/provider reserve and is
 * not available to database or protocol accounting.
 */
public final class RiverDaemonResources {
  private static final long DATABASE_BYTES = 256_000_000L;
  private static final long DELIVERY_BYTES = 64_000_000L;
  private static final long LOCK_PROVIDER_BYTES = 8_000_000L;
  private static final long VERSION_WORKSPACE_BYTES = 8_000_000L;
  private static final long PAGE_CACHE_BYTES = 32_000_000L;
  private static final long STAGING_BYTES = 8_000_000L;
  // The delivery bound is expressed in the owning format's fixed page geometry, not a
  // benchmark-shaped page count.  The compiler still checks the resulting metadata capacity.
  private static final long STAGED_PAGES = DELIVERY_BYTES / PageCodec.PAGE_BYTES;
  /** Explicit process budget for protocol retention; the engine profile remains 256 MB. */
  private static final long MAXIMUM_PROTOCOL_BYTES = 512_000_000L;
  /** Heap reserve retained for lifecycle objects and native/provider bookkeeping. */
  private static final long HEAP_RESERVE_BYTES = 128_000_000L;

  private RiverDaemonResources() {
  }

  public static StatusCode compile(int maximumConnections, Result result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (maximumConnections <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (!ProtocolMemoryBudget.supportsServerConnections(maximumConnections)) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    ProtocolMemoryBudget protocol = ProtocolMemoryBudget.forServer(maximumConnections);
    if (protocol.maximumBytes() > MAXIMUM_PROTOCOL_BYTES) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    long requiredHeap = DATABASE_BYTES + protocol.maximumBytes() + HEAP_RESERVE_BYTES;
    if (requiredHeap < 0 || requiredHeap > Runtime.getRuntime().maxMemory()) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    DatabaseResourcePlanRequest request = new DatabaseResourcePlanRequest()
        .memory(DATABASE_BYTES, 0, 0, 0, DELIVERY_BYTES)
        .lockProviderBytes(LOCK_PROVIDER_BYTES)
        .versionWorkspaceBytes(VERSION_WORKSPACE_BYTES)
        .indexedPageCache(PAGE_CACHE_BYTES, STAGING_BYTES)
        .capacity(maximumConnections, Integer.MAX_VALUE, STAGED_PAGES, DELIVERY_BYTES)
        .maximumDelivery(Integer.MAX_VALUE, STAGED_PAGES, DELIVERY_BYTES);
    DatabaseResourcePlan.Result planResult = new DatabaseResourcePlan.Result();
    StatusCode status = DatabaseResourcePlan.compile(request, planResult);
    if (!status.isOk()) return status;
    result.complete(request, planResult.plan(), maximumConnections, protocol.maximumBytes());
    return StatusCode.OK;
  }

  public static final class Result {
    private DatabaseResourcePlan plan;
    private DatabaseResourcePlanRequest request;
    private int maximumConnections;
    private long protocolBytes;

    public void reset() {
      plan = null;
      request = null;
      maximumConnections = 0;
      protocolBytes = 0;
    }

    void complete(
        DatabaseResourcePlanRequest admittedRequest,
        DatabaseResourcePlan opened,
        int connections,
        long protocol) {
      request = admittedRequest;
      plan = opened;
      maximumConnections = connections;
      protocolBytes = protocol;
    }

    public DatabaseResourcePlan plan() { return plan; }
    public DatabaseResourcePlanRequest request() { return request; }
    public int maximumConnections() { return maximumConnections; }
    public int maximumActiveTransactions() { return maximumConnections; }
    public long protocolBytes() { return protocolBytes; }
  }
}
