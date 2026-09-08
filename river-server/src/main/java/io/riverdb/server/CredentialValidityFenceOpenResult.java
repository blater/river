package io.riverdb.server;

import io.riverdb.base.error.StatusCode;

/** Caller-owned transfer result for one credential-validity fence. */
public final class CredentialValidityFenceOpenResult {
  private CredentialValidityFence fence;

  public void reset() {
    fence = null;
  }

  public StatusCode complete(CredentialValidityFence opened) {
    if (opened == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    fence = opened;
    return StatusCode.OK;
  }

  public CredentialValidityFence fence() {
    return fence;
  }
}
