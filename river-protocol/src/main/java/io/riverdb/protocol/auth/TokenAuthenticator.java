package io.riverdb.protocol.auth;

import io.riverdb.base.error.StatusCode;
import io.riverdb.protocol.ProtocolFrame;
import java.security.MessageDigest;
import java.util.Arrays;

/** Stores only a token hash and verifies one TLS-bound proof at a time. */
public final class TokenAuthenticator {
  private final byte[] tokenKey = new byte[TokenProof.PROOF_BYTES];
  private final byte[] offeredProof = new byte[TokenProof.PROOF_BYTES];
  private final byte[] expectedProof = new byte[TokenProof.PROOF_BYTES];

  private TokenAuthenticator() {
  }

  public static StatusCode create(
      byte[] token,
      int tokenBytes,
      TokenAuthenticatorOpenResult result) {
    if (result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    if (token == null
        || tokenBytes < TokenProof.MINIMUM_TOKEN_BYTES
        || tokenBytes > TokenProof.MAXIMUM_TOKEN_BYTES
        || tokenBytes > token.length) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    TokenAuthenticator authenticator = new TokenAuthenticator();
    StatusCode status = TokenProof.hashToken(token, tokenBytes, authenticator.tokenKey);
    return status.isOk() ? result.complete(authenticator) : status;
  }

  public synchronized StatusCode verify(
      ProtocolFrame frame,
      long challengeHigh,
      long challengeLow,
      byte[] channelBinding) {
    if (frame == null || frame.payloadBytes() != TokenProof.PROOF_BYTES) {
      if (frame != null) {
        frame.erasePayload();
      }
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = frame.copyPayloadTo(offeredProof);
    if (status.isOk()) {
      status = TokenProof.computeFromKey(
          tokenKey,
          challengeHigh,
          challengeLow,
          channelBinding,
          expectedProof);
    }
    boolean matches = status.isOk()
        && MessageDigest.isEqual(offeredProof, expectedProof);
    Arrays.fill(offeredProof, (byte) 0);
    Arrays.fill(expectedProof, (byte) 0);
    StatusCode erased = frame.erasePayload();
    if (!erased.isOk()) {
      return erased;
    }
    if (!status.isOk()) {
      return status;
    }
    return matches ? StatusCode.OK : StatusCode.INVALID_EXTERNAL_INPUT;
  }
}
