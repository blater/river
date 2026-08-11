package io.riverdb.engine.api;

import io.riverdb.base.error.StatusCode;

/** Caller-owned output for one active streaming query. */
public final class QueryOpenResult {
  private RiverQuery query;

  public void reset() {
    query = null;
  }

  public StatusCode complete(RiverQuery opened) {
    if (opened == null || !opened.isActive()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    query = opened;
    return StatusCode.OK;
  }

  public RiverQuery query() {
    return query;
  }
}
