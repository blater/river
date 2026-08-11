package io.riverdb.client;

import io.riverdb.base.error.StatusCode;

/** Caller-owned result for one loopback River client connection. */
public final class RiverClientOpenResult {
  private RiverClientConnection connection;

  public void reset() {
    connection = null;
  }

  public StatusCode complete(RiverClientConnection opened) {
    if (opened == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    connection = opened;
    return StatusCode.OK;
  }

  public RiverClientConnection connection() {
    return connection;
  }
}
