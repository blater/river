package io.riverdb.engine.api;

import io.riverdb.base.error.StatusCode;

/**
 * Single-owner streaming query capability. An OK unavailable row is end of stream.
 */
public interface RiverQuery {
  StatusCode next(RowResult result);

  StatusCode close(CommandResult result);

  boolean isActive();

  int columnCount();

  long rowsReturned();
}
