package io.riverdb.engine.runtime;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.engine.runtime.materialized.SqlMaterializedPagePool;
import io.riverdb.engine.runtime.materialized.SqlMaterializedPagePoolResult;
import io.riverdb.engine.runtime.materialized.SqlMaterializedScratchRuntime;
import java.nio.file.Path;

/** Database-owned, schema-neutral lifetime boundary for SQL scratch resources. */
public final class SqlDatabaseRuntime {
  private final RiverRuntimeConfig config;
  private final SqlMaterializedPagePool materializedPages;
  private final SqlMaterializedScratchRuntime materializedScratch;
  private long reservedShapeBytes;
  private int leases;
  private boolean closing;
  private boolean closed;

  private SqlDatabaseRuntime(
      RiverRuntimeConfig runtimeConfig,
      SqlMaterializedPagePool pagePool,
      SqlMaterializedScratchRuntime scratchRuntime) {
    config = runtimeConfig;
    materializedPages = pagePool;
    materializedScratch = scratchRuntime;
  }

  public static StatusCode create(
      RiverRuntimeConfig config,
      Path authoritativePrimaryPath,
      DatabaseIncarnation database,
      OpenResult result,
      StatusDetail detail) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (detail != null) detail.reset();
    if (config == null || authoritativePrimaryPath == null
        || database == null || !database.isValid()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    SqlMaterializedPagePoolResult poolResult = new SqlMaterializedPagePoolResult();
    StatusCode status = SqlMaterializedPagePool.create(
        config.pageBytes(), config.cachePages(), poolResult);
    if (!status.isOk()) return status;
    SqlMaterializedScratchRuntime.OpenResult scratchResult =
        new SqlMaterializedScratchRuntime.OpenResult();
    status = SqlMaterializedScratchRuntime.create(
        config.spillDirectory(), authoritativePrimaryPath, database,
        poolResult.pool(), scratchResult, detail);
    if (!status.isOk()) {
      poolResult.pool().close();
      return status;
    }
    SqlDatabaseRuntime runtime;
    try {
      runtime = new SqlDatabaseRuntime(
          config, poolResult.pool(), scratchResult.runtime());
    } catch (OutOfMemoryError failure) {
      scratchResult.runtime().close(detail);
      poolResult.pool().close();
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    result.set(runtime);
    return StatusCode.OK;
  }

  public synchronized StatusCode acquire(SqlRuntimeLeaseResult result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (closed || closing) return StatusCode.CLOSED;
    if (leases == Integer.MAX_VALUE) return StatusCode.RESOURCE_EXHAUSTED;
    SqlRuntimeLease lease;
    try {
      lease = new SqlRuntimeLease(this);
    } catch (OutOfMemoryError failure) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    leases++;
    result.set(lease);
    return StatusCode.OK;
  }

  public synchronized StatusCode prepareClose() {
    if (closed) return StatusCode.CLOSED;
    if (leases != 0) return StatusCode.CONFLICT;
    if (reservedShapeBytes != 0) return StatusCode.INVARIANT_BROKEN;
    closing = true;
    return StatusCode.OK;
  }

  public synchronized StatusCode completeClose() {
    if (closed) return StatusCode.CLOSED;
    if (!closing || leases != 0 || reservedShapeBytes != 0) {
      return StatusCode.INVARIANT_BROKEN;
    }
    StatusCode status = materializedScratch.close(null);
    StatusCode pageStatus = materializedPages.close();
    if (status.isOk()) status = pageStatus;
    if (status.isOk()) {
      closed = true;
      closing = false;
    }
    return status;
  }

  synchronized StatusCode release(long reserved) {
    if (leases <= 0) return StatusCode.INVARIANT_BROKEN;
    if (reserved < 0 || reserved > reservedShapeBytes) {
      return StatusCode.INVARIANT_BROKEN;
    }
    reservedShapeBytes -= reserved;
    leases--;
    return StatusCode.OK;
  }

  synchronized StatusCode reserve(long bytes) {
    if (bytes <= 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (closed || closing) return StatusCode.CLOSED;
    long available = config.sessionShapeCacheBytes() - reservedShapeBytes;
    if (bytes > available) return StatusCode.RESOURCE_EXHAUSTED;
    reservedShapeBytes += bytes;
    return StatusCode.OK;
  }

  synchronized StatusCode releaseReserved(long bytes) {
    if (bytes <= 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (bytes > reservedShapeBytes) return StatusCode.INVARIANT_BROKEN;
    reservedShapeBytes -= bytes;
    return StatusCode.OK;
  }

  public synchronized long reservedShapeBytes() {
    return reservedShapeBytes;
  }

  public long sessionShapeCacheBudgetBytes() {
    return config.sessionShapeCacheBytes();
  }

  RiverRuntimeConfig config() {
    return config;
  }

  SqlMaterializedPagePool materializedPages() {
    return materializedPages;
  }

  SqlMaterializedScratchRuntime materializedScratch() {
    return materializedScratch;
  }

  public static final class OpenResult {
    private SqlDatabaseRuntime runtime;

    public void reset() { runtime = null; }
    void set(SqlDatabaseRuntime opened) { runtime = opened; }
    public SqlDatabaseRuntime runtime() { return runtime; }
  }
}
