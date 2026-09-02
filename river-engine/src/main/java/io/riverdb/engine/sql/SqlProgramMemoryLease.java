package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.RetainedMemoryLease;

/** Independent resize lease charged to one SQL session's retained-memory budget. */
public final class SqlProgramMemoryLease implements RetainedMemoryLease {
  private final SqlRetainedBudget budget;
  private long bytes;

  public SqlProgramMemoryLease(SqlRetainedBudget retainedBudget) {
    if (retainedBudget == null) throw new IllegalArgumentException("retainedBudget");
    budget = retainedBudget;
  }

  @Override
  public StatusCode resize(long requested) {
    if (requested < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (requested == bytes) return StatusCode.OK;
    StatusCode status = requested > bytes
        ? budget.reserveRetainedBytes(requested - bytes)
        : budget.releaseRetainedBytes(bytes - requested);
    if (status.isOk()) bytes = requested;
    return status;
  }

  @Override public StatusCode awaitResize(long requested) { return resize(requested); }
  @Override public long retainedBytes() { return bytes; }
}
