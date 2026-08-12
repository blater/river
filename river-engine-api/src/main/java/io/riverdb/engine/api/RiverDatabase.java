package io.riverdb.engine.api;

import io.riverdb.base.error.StatusCode;

/**
 * Public database lifecycle boundary shared by embedded and remote engines.
 * Close returns CONFLICT while an API session remains open.
 */
public interface RiverDatabase {
  StatusCode createSession(SessionOpenResult result);

  StatusCode close();
}
