package io.riverdb.engine.api;

import io.riverdb.base.error.StatusCode;

/** Caller-owned output for an opened River session. */
public final class SessionOpenResult {
  private RiverSession session;

  public void reset() {
    session = null;
  }

  public StatusCode complete(RiverSession opened) {
    if (opened == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    session = opened;
    return StatusCode.OK;
  }

  public RiverSession session() {
    return session;
  }
}
