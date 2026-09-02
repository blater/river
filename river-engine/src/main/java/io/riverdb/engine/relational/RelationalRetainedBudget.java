package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;

/** Ownership boundary for session-retained relational workspaces. */
public interface RelationalRetainedBudget {
  StatusCode reserve(long bytes);

  void rollback(long bytes);
}
