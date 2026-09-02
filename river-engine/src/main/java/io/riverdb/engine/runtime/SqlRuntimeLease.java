package io.riverdb.engine.runtime;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.runtime.materialized.SqlMaterializedPagePool;
import io.riverdb.engine.runtime.materialized.SqlMaterializedScratchRuntime;

/** Schema-neutral ownership claim held by one published SQL session. */
public final class SqlRuntimeLease {
  private final SqlDatabaseRuntime owner;
  private long reservedBytes;
  private boolean closed;

  SqlRuntimeLease(SqlDatabaseRuntime runtime) {
    owner = runtime;
  }

  public RiverRuntimeConfig config() {
    return owner.config();
  }

  public SqlMaterializedPagePool materializedPages() {
    return owner.materializedPages();
  }

  public SqlMaterializedScratchRuntime materializedScratch() {
    return owner.materializedScratch();
  }

  public synchronized StatusCode reserve(long bytes) {
    if (bytes <= 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (closed) return StatusCode.CLOSED;
    if (Long.MAX_VALUE - reservedBytes < bytes) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = owner.reserve(bytes);
    if (status.isOk()) reservedBytes += bytes;
    return status;
  }

  public synchronized StatusCode releaseReserved(long bytes) {
    if (bytes <= 0 || bytes > reservedBytes) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (closed) return StatusCode.CLOSED;
    StatusCode status = owner.releaseReserved(bytes);
    if (status.isOk()) reservedBytes -= bytes;
    return status;
  }

  public synchronized long reservedBytes() {
    return reservedBytes;
  }

  public synchronized StatusCode close() {
    if (closed) return StatusCode.CLOSED;
    StatusCode status = owner.release(reservedBytes);
    if (status.isOk()) {
      reservedBytes = 0;
      closed = true;
    }
    return status;
  }
}
