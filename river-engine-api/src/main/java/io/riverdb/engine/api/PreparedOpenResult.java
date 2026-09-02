package io.riverdb.engine.api;

import io.riverdb.base.error.StatusCode;

/** Caller-owned metadata for one session-owned prepared statement handle. */
public final class PreparedOpenResult {
  private long handle;
  private int parameterCount;
  private boolean query;

  public void reset() {
    handle = 0;
    parameterCount = 0;
    query = false;
  }

  public StatusCode complete(long preparedHandle, int parameters, boolean queryStatement) {
    if (preparedHandle <= 0 || parameters < 0 || parameters > ParameterSet.MAXIMUM_PARAMETERS) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    handle = preparedHandle;
    parameterCount = parameters;
    query = queryStatement;
    return StatusCode.OK;
  }

  public long handle() { return handle; }
  public int parameterCount() { return parameterCount; }
  public boolean query() { return query; }
}
