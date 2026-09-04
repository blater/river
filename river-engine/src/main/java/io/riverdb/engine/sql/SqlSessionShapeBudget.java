package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalRetainedBudget;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.engine.runtime.SqlRuntimeLease;
import java.util.ArrayList;

/** Session-owned reservation view over the configured database-wide shape budget. */
final class SqlSessionShapeBudget implements RelationalRetainedBudget {
  private final SqlRuntimeLease lease;
  private final long maximum;
  private final SqlMaterializedStatement materialized;
  private final ArrayList<SqlRetainedReclaimer> reclaimers = new ArrayList<>();
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
    StatusCode status = reserveOnce(bytes);
    if (status != StatusCode.RESOURCE_EXHAUSTED) return status;
    status = reclaimInactive();
    return status.isOk() ? reserveOnce(bytes) : status;
  }

  StatusCode registerReclaimer(SqlRetainedReclaimer reclaimer) {
    if (reclaimer == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    for (int index = 0; index < reclaimers.size(); index++) {
      if (reclaimers.get(index) == reclaimer) return StatusCode.OK;
    }
    try {
      reclaimers.add(reclaimer);
      return StatusCode.OK;
    } catch (OutOfMemoryError failure) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  private StatusCode reserveOnce(long bytes) {
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

  private StatusCode reclaimInactive() {
    long before = retained;
    for (int index = 0; index < reclaimers.size(); index++) {
      SqlRetainedReclaimer reclaimer = reclaimers.get(index);
      long reclaimed = reclaimer.reclaimableRetainedBytes();
      if (reclaimed < 0 || reclaimed > retained) return StatusCode.INVARIANT_BROKEN;
      if (reclaimed > 0) {
        StatusCode status = release(reclaimed);
        if (!status.isOk()) return status;
        reclaimer.releaseRetainedStorage();
      }
    }
    return retained < before ? StatusCode.OK : StatusCode.RESOURCE_EXHAUSTED;
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

  long maximumReplacementBytes(long ownedRetainedBytes) {
    return ownedRetainedBytes < 0 || ownedRetainedBytes > retained
        ? -1 : maximum - (retained - ownedRetainedBytes);
  }

  SqlMaterializedStatement materialized() { return materialized; }
  StatusCode closeMaterialized(StatusDetail detail) { return materialized.close(detail); }
}
