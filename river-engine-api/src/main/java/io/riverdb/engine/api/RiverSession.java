package io.riverdb.engine.api;

import io.riverdb.base.error.StatusCode;

/**
 * Single-owner command boundary with one active streaming query at a time.
 * Closing aborts an explicit transaction that the session still owns.
 */
public interface RiverSession {
  StatusCode execute(String sql, CommandResult result);

  StatusCode execute(String sql, ParameterSet parameters, CommandResult result);

  StatusCode beginQuery(String sql, QueryOpenResult result);

  StatusCode beginQuery(
      String sql, ParameterSet parameters, QueryOpenResult result);

  StatusCode close();
}
