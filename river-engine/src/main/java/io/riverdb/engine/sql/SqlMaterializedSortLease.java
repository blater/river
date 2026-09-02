package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;

/** Retry-retained exact page reservation for one materialized sort operator. */
final class SqlMaterializedSortLease {
  private final SqlMaterializedSortReservation reservation =
      new SqlMaterializedSortReservation();
  private SqlMaterializedStatement owner;

  StatusCode acquire(SqlMaterializedStatement statement) {
    if (statement == null || owner != null) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = statement.reserveSortPages(reservation);
    if (status.isOk()) owner = statement;
    return status;
  }

  StatusCode release(StatusCode prior) {
    if (owner == null) return prior;
    StatusCode status = owner.releaseSortPages(reservation);
    if (status.isOk()) owner = null;
    return prior.isOk() ? status : prior;
  }

  boolean active() { return owner != null; }
}
