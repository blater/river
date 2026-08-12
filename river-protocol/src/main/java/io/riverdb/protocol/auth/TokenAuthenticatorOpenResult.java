package io.riverdb.protocol.auth;

import io.riverdb.base.error.StatusCode;

/** Caller-owned result for a hashed token verifier. */
public final class TokenAuthenticatorOpenResult {
  private TokenAuthenticator authenticator;

  public void reset() {
    authenticator = null;
  }

  public StatusCode complete(TokenAuthenticator opened) {
    if (opened == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    authenticator = opened;
    return StatusCode.OK;
  }

  public TokenAuthenticator authenticator() {
    return authenticator;
  }
}
