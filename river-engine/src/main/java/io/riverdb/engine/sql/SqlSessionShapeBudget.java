package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalRetainedBudget;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.engine.runtime.SqlRuntimeLease;

/** Session-owned reservation view over the configured database-wide shape budget. */
final class SqlSessionShapeBudget implements RelationalRetainedBudget {
  private final SqlRuntimeLease lease;
  private final long maximum;
  private final SqlMaterializedStatement materialized;
  private long retained;

  SqlSessionShapeBudget(SqlRuntimeLease runtimeLease) {
    lease = runtimeLease;
    maximum = runtimeLease == null
        ? Long.MAX_VALUE : runtimeLease.config().sessionShapeCacheBytes();
    materialized = new SqlMaterializedStatement(runtimeLease);
  }

  @Override
  public StatusCode reserve(long bytes) {
    if (bytes <= 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (bytes > maximum - retained) return StatusCode.RESOURCE_EXHAUSTED;
    if (lease == null) {
      retained += bytes;
      return StatusCode.OK;
    }
    StatusCode status = lease.reserve(bytes);
    if (status.isOk()) {
      retained += bytes;
    }
    return status;
  }

  @Override
  public void rollback(long bytes) {
    release(bytes);
  }

  StatusCode release(long bytes) {
    if (bytes <= 0 || bytes > retained) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (lease == null) {
      retained -= bytes;
      return StatusCode.OK;
    }
    StatusCode status = lease.releaseReserved(bytes);
    if (status.isOk()) retained -= bytes;
    return status;
  }

  long retainedBytes() { return retained; }
  long maximumBytes() { return maximum; }
  SqlMaterializedStatement materialized() { return materialized; }
  StatusCode closeMaterialized(StatusDetail detail) { return materialized.close(detail); }
}
