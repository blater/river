package io.riverdb.bench.harness;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class WorkloadChecksums {
  private WorkloadChecksums() {
  }

  static String sha256(byte[] bytes) {
    return hex(sha256Digest().digest(bytes));
  }

  static MessageDigest sha256Digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException exception) {
      throw new AssertionError("SHA-256 is required by the JDK", exception);
    }
  }

  static String hex(byte[] digest) {
    return HexFormat.of().formatHex(digest);
  }
}
