package io.riverdb.client;

import io.riverdb.base.error.StatusCode;

/** Caller-owned result for one validated riverd client configuration. */
public final class RiverClientConfigurationResult {
  private RiverClientConfiguration configuration;

  public void reset() {
    configuration = null;
  }

  public StatusCode complete(RiverClientConfiguration opened) {
    if (opened == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    configuration = opened;
    return StatusCode.OK;
  }

  public RiverClientConfiguration configuration() {
    return configuration;
  }
}
