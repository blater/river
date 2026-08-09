package io.riverdb.bench.harness;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class WorkloadChecksums {
  private WorkloadChecksums() {
  }

  static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException exception) {
      throw new AssertionError("SHA-256 is required by the JDK", exception);
    }
  }
}
