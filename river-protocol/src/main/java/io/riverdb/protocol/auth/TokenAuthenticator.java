package io.riverdb.protocol.auth;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.SessionPermissions;
import io.riverdb.protocol.ProtocolFrame;
import java.security.MessageDigest;
import java.util.Arrays;

/** Stores only a token hash and verifies one TLS-bound proof at a time. */
public final class TokenAuthenticator {
  private final byte[] tokenKey = new byte[TokenProof.PROOF_BYTES];
  private final byte[] offeredProof = new byte[TokenProof.PROOF_BYTES];
  private final byte[] expectedProof = new byte[TokenProof.PROOF_BYTES];
  private long principalId;
  private int permissions;

  private TokenAuthenticator() {
  }

  public static StatusCode create(
      byte[] token,
      int tokenBytes,
      TokenAuthenticatorOpenResult result) {
    return create(token, tokenBytes, 1, SessionPermissions.ALL, result);
  }

  public static StatusCode create(
      byte[] token,
      int tokenBytes,
      long principalId,
      int permissions,
      TokenAuthenticatorOpenResult result) {
    if (result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    if (token == null
        || tokenBytes < TokenProof.MINIMUM_TOKEN_BYTES
        || tokenBytes > TokenProof.MAXIMUM_TOKEN_BYTES
        || tokenBytes > token.length
        || principalId <= 0
        || !SessionPermissions.valid(permissions)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    TokenAuthenticator authenticator = new TokenAuthenticator();
    authenticator.principalId = principalId;
    authenticator.permissions = permissions;
    StatusCode status = TokenProof.hashToken(token, tokenBytes, authenticator.tokenKey);
    return status.isOk() ? result.complete(authenticator) : status;
  }

  public long principalId() {
    return principalId;
  }

  public int permissions() {
    return permissions;
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
