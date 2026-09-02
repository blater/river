package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.sql.SqlStatementTemplate;

/** Reusable result of prepared SQL syntax and structural binding validation. */
public final class SqlPreparedValidationResult {
  private int parameterCount;
  private boolean query;
  private SqlPreparedPlan plan;
  private SqlRetainedBudget reservationOwner;
  private long reservedBytes;

  public StatusCode reset() {
    StatusCode status = reservationOwner == null
        ? StatusCode.OK : reservationOwner.releaseRetainedBytes(reservedBytes);
    parameterCount = 0;
    query = false;
    plan = null;
    reservationOwner = null;
    reservedBytes = 0;
    return status;
  }

  public StatusCode complete(
      SqlStatementTemplate compiled,
      boolean queryStatement,
      long catalogGeneration,
      SqlRetainedBudget owner,
      long bytes) {
    if (compiled == null || catalogGeneration <= 0 || owner == null || bytes <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    SqlPreparedPlan prepared;
    try {
      prepared = new SqlPreparedPlan(compiled, queryStatement, catalogGeneration);
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    if (prepared.byteCharge() != bytes) return StatusCode.INVARIANT_BROKEN;
    parameterCount = prepared.parameterCount();
    query = queryStatement;
    plan = prepared;
    reservationOwner = owner;
    reservedBytes = bytes;
    return StatusCode.OK;
  }

  public long transferReservation(SqlRetainedBudget owner) {
    if (owner == null || owner != reservationOwner || plan == null) return 0;
    long bytes = reservedBytes;
    reservationOwner = null;
    reservedBytes = 0;
    return bytes;
  }

  public int parameterCount() { return parameterCount; }
  public boolean query() { return query; }
  public SqlPreparedPlan plan() { return plan; }
}
