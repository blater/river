package io.riverdb.protocol.auth;

import io.riverdb.base.error.StatusCode;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** TLS-session-bound challenge proof derived from a caller-owned token. */
public final class TokenProof {
  public static final int PROOF_BYTES = 32;
  public static final int MINIMUM_TOKEN_BYTES = 16;
  public static final int MAXIMUM_TOKEN_BYTES = 256;

  private TokenProof() {
  }

  public static StatusCode compute(
      byte[] token,
      int tokenBytes,
      long challengeHigh,
      long challengeLow,
      byte[] channelBinding,
      byte[] output) {
    if (token == null
        || tokenBytes < MINIMUM_TOKEN_BYTES
        || tokenBytes > MAXIMUM_TOKEN_BYTES
        || tokenBytes > token.length
        || channelBinding == null
        || channelBinding.length == 0
        || output == null
        || output.length < PROOF_BYTES
        || challengeHigh == 0 && challengeLow == 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    Arrays.fill(output, 0, PROOF_BYTES, (byte) 0);
    byte[] key = new byte[PROOF_BYTES];
    try {
      StatusCode status = hashToken(token, tokenBytes, key);
      return status.isOk()
          ? computeFromKey(
              key, challengeHigh, challengeLow, channelBinding, output)
          : status;
    } finally {
      Arrays.fill(key, (byte) 0);
    }
  }

  static StatusCode hashToken(byte[] token, int tokenBytes, byte[] output) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(token, 0, tokenBytes);
      digest.digest(output, 0, PROOF_BYTES);
      return StatusCode.OK;
    } catch (GeneralSecurityException failure) {
      return StatusCode.INVARIANT_BROKEN;
    }
  }

  static StatusCode computeFromKey(
      byte[] key,
      long challengeHigh,
      long challengeLow,
      byte[] channelBinding,
      byte[] output) {
    Arrays.fill(output, 0, PROOF_BYTES, (byte) 0);
    byte[] fixed = new byte[20];
    putInt(fixed, 0, 1);
    putLong(fixed, 4, challengeHigh);
    putLong(fixed, 12, challengeLow);
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      mac.update(fixed);
      mac.update(channelBinding);
      mac.doFinal(output, 0);
      return StatusCode.OK;
    } catch (GeneralSecurityException failure) {
      return StatusCode.INVARIANT_BROKEN;
    } finally {
      Arrays.fill(fixed, (byte) 0);
    }
  }

  private static void putInt(byte[] target, int offset, int value) {
    target[offset] = (byte) (value >>> 24);
    target[offset + 1] = (byte) (value >>> 16);
    target[offset + 2] = (byte) (value >>> 8);
    target[offset + 3] = (byte) value;
  }

  private static void putLong(byte[] target, int offset, long value) {
    target[offset] = (byte) (value >>> 56);
    target[offset + 1] = (byte) (value >>> 48);
    target[offset + 2] = (byte) (value >>> 40);
    target[offset + 3] = (byte) (value >>> 32);
    target[offset + 4] = (byte) (value >>> 24);
    target[offset + 5] = (byte) (value >>> 16);
    target[offset + 6] = (byte) (value >>> 8);
    target[offset + 7] = (byte) value;
  }
}
