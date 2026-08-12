package io.riverdb.server;

import io.riverdb.base.error.StatusCode;

/** Caller-owned result for starting a loopback-only River server. */
public final class LoopbackServerOpenResult {
  private LoopbackRiverServer server;

  public void reset() {
    server = null;
  }

  public StatusCode complete(LoopbackRiverServer opened) {
    if (opened == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    server = opened;
    return StatusCode.OK;
  }

  public LoopbackRiverServer server() {
    return server;
  }
}
