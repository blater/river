package io.riverdb.server.app;

import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.engine.api.SessionPermissions;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;

/** Owns the fixed security manifest format and its sensitive scratch buffers. */
final class RiverDaemonCredentialManifest {
  static final int MAX_BYTES = 8192;
  private static final String[] KEYS = {
      "format", "database-incarnation-high", "database-incarnation-low",
      "credential-generation", "principal-id", "permission-mask", "token-algorithm",
      "key-algorithm", "signature-algorithm", "certificate-not-before-epoch-second",
      "certificate-not-after-epoch-second", "token-file", "private-key-file",
      "server-certificate-file", "token-sha256", "private-key-sha256",
      "server-certificate-sha256"
  };

  private RiverDaemonCredentialManifest() {
  }

  static byte[] encode(
      RiverDaemonCredentials.Material material,
      DatabaseIncarnation incarnation,
      String generationName,
      byte[] privateKey,
      byte[] certificate) throws Exception {
    ManifestWriter writer = new ManifestWriter();
    try {
      writer.field("format", "riverd-security-v1");
      writer.field("database-incarnation-high", Long.toString(incarnation.high()));
      writer.field("database-incarnation-low", Long.toString(incarnation.low()));
      writer.field("credential-generation", Long.toString(material.generation()));
      writer.field("principal-id", "1");
      writer.field("permission-mask", Integer.toString(SessionPermissions.ALL));
      writer.field("token-algorithm", "raw-256");
      writer.field("key-algorithm", "ec-secp256r1");
      writer.field("signature-algorithm", "sha256-with-ecdsa");
      writer.field("certificate-not-before-epoch-second",
          Long.toString(material.certificate().getNotBefore().toInstant().getEpochSecond()));
      writer.field("certificate-not-after-epoch-second",
          Long.toString(material.certificate().getNotAfter().toInstant().getEpochSecond()));
      writer.field("token-file", "generations/" + generationName + "/token.bin");
      writer.field("private-key-file", "generations/" + generationName + "/server-private-key.pkcs8");
      writer.field("server-certificate-file", "generations/" + generationName
          + "/server-certificate.der");
      writer.digestField("token-sha256", material.token());
      writer.digestField("private-key-sha256", privateKey);
      writer.digestField("server-certificate-sha256", certificate);
      return writer.finish();
    } finally {
      writer.clear();
    }
  }

  static Parsed parse(byte[] bytes) {
    if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) return null;
    String[] fields = new String[14];
    byte[][] digests = new byte[3][];
    int position = 0;
    int prefixEnd = -1;
    try {
      for (int index = 0; index < KEYS.length; index++) {
        int lineEnd = lineEnd(bytes, position);
        if (lineEnd < 0) return invalid(digests);
        int equals = equalsAt(bytes, position, lineEnd);
        if (equals <= position || !asciiEquals(bytes, position, equals, KEYS[index])) {
          return invalid(digests);
        }
        int valueStart = equals + 1;
        int valueLength = lineEnd - valueStart;
        if (index >= 14) {
          if (valueLength != 64) return invalid(digests);
          digests[index - 14] = parseHex(bytes, valueStart, valueLength);
          if (digests[index - 14] == null) return invalid(digests);
        } else {
          fields[index] = decodeScalar(bytes, valueStart, valueLength);
          if (fields[index] == null) return invalid(digests);
        }
        position = lineEnd + 1;
        if (index == KEYS.length - 1) prefixEnd = position;
      }
      int lineEnd = lineEnd(bytes, position);
      if (lineEnd < 0 || lineEnd != bytes.length - 1) return invalid(digests);
      int equals = equalsAt(bytes, position, lineEnd);
      if (equals <= position || !asciiEquals(bytes, position, equals, "record-sha256")
          || lineEnd - equals - 1 != 64) return invalid(digests);
      byte[] recordDigest = parseHex(bytes, equals + 1, 64);
      if (recordDigest == null) return invalid(digests);
      byte[] actual = sha256(bytes, 0, prefixEnd);
      boolean valid = MessageDigest.isEqual(actual, recordDigest)
          && "riverd-security-v1".equals(fields[0]);
      Arrays.fill(actual, (byte) 0);
      Arrays.fill(recordDigest, (byte) 0);
      if (!valid) return invalid(digests);
      return new Parsed(fields, digests[0], digests[1], digests[2]);
    } catch (CharacterCodingException | RuntimeException failure) {
      return invalid(digests);
    }
  }

  static String hexDigest(byte[] bytes) {
    byte[] digest = sha256(bytes, 0, bytes.length);
    String value = HexFormat.of().formatHex(digest);
    Arrays.fill(digest, (byte) 0);
    return value;
  }

  static boolean matchesDigest(byte[] bytes, byte[] expected) {
    if (bytes == null || expected == null || expected.length != 32) return false;
    byte[] actual = null;
    try {
      actual = sha256(bytes, 0, bytes.length);
      return MessageDigest.isEqual(actual, expected);
    } catch (RuntimeException failure) {
      return false;
    } finally {
      if (actual != null) Arrays.fill(actual, (byte) 0);
    }
  }

  private static Parsed invalid(byte[][] digests) {
    for (byte[] digest : digests) {
      if (digest != null) Arrays.fill(digest, (byte) 0);
    }
    return null;
  }

  private static int lineEnd(byte[] bytes, int start) {
    for (int index = start; index < bytes.length; index++) {
      if (bytes[index] == '\n') return index;
    }
    return -1;
  }

  private static int equalsAt(byte[] bytes, int start, int end) {
    for (int index = start; index < end; index++) {
      if (bytes[index] == '=') return index;
    }
    return -1;
  }

  private static boolean asciiEquals(byte[] bytes, int start, int end, String expected) {
    if (end - start != expected.length()) return false;
    for (int index = 0; index < expected.length(); index++) {
      if (bytes[start + index] != (byte) expected.charAt(index)) return false;
    }
    return true;
  }

  private static String decodeScalar(byte[] bytes, int start, int length)
      throws CharacterCodingException {
    return StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes, start, length)).toString();
  }

  private static byte[] parseHex(byte[] bytes, int start, int length) {
    if (length != 64) return null;
    byte[] result = new byte[32];
    for (int index = 0; index < result.length; index++) {
      int high = hexValue(bytes[start + index * 2]);
      int low = hexValue(bytes[start + index * 2 + 1]);
      if (high < 0 || low < 0) {
        Arrays.fill(result, (byte) 0);
        return null;
      }
      result[index] = (byte) ((high << 4) | low);
    }
    return result;
  }

  private static int hexValue(byte value) {
    if (value >= '0' && value <= '9') return value - '0';
    if (value >= 'a' && value <= 'f') return value - 'a' + 10;
    return -1;
  }

  private static byte[] sha256(byte[] bytes, int offset, int length) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(bytes, offset, length);
      return digest.digest();
    } catch (java.security.NoSuchAlgorithmException failure) {
      throw new IllegalStateException("SHA-256 unavailable", failure);
    }
  }

  static final class Parsed {
    final String[] publicFields;
    private byte[] tokenDigest;
    private byte[] privateKeyDigest;
    private byte[] certificateDigest;

    Parsed(
        String[] publicFields,
        byte[] tokenDigest,
        byte[] privateKeyDigest,
        byte[] certificateDigest) {
      this.publicFields = publicFields;
      this.tokenDigest = tokenDigest;
      this.privateKeyDigest = privateKeyDigest;
      this.certificateDigest = certificateDigest;
    }

    byte[] tokenDigest() {
      return tokenDigest;
    }

    byte[] privateKeyDigest() {
      return privateKeyDigest;
    }

    byte[] certificateDigest() {
      return certificateDigest;
    }

    void clear() {
      if (tokenDigest != null) Arrays.fill(tokenDigest, (byte) 0);
      if (privateKeyDigest != null) Arrays.fill(privateKeyDigest, (byte) 0);
      if (certificateDigest != null) Arrays.fill(certificateDigest, (byte) 0);
      tokenDigest = null;
      privateKeyDigest = null;
      certificateDigest = null;
    }
  }

  private static final class ManifestWriter {
    private final byte[] bytes = new byte[MAX_BYTES];
    private int position;

    void field(String key, String value) {
      appendAscii(key);
      appendByte((byte) '=');
      byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
      try {
        append(encoded, 0, encoded.length);
      } finally {
        Arrays.fill(encoded, (byte) 0);
      }
      appendByte((byte) '\n');
    }

    void digestField(String key, byte[] value) throws Exception {
      appendAscii(key);
      appendByte((byte) '=');
      byte[] digest = sha256(value, 0, value.length);
      appendHex(digest);
      Arrays.fill(digest, (byte) 0);
      appendByte((byte) '\n');
    }

    byte[] finish() throws Exception {
      byte[] digest = sha256(bytes, 0, position);
      appendAscii("record-sha256");
      appendByte((byte) '=');
      appendHex(digest);
      appendByte((byte) '\n');
      Arrays.fill(digest, (byte) 0);
      return Arrays.copyOf(bytes, position);
    }

    void clear() {
      Arrays.fill(bytes, (byte) 0);
    }

    private void appendAscii(String value) {
      for (int index = 0; index < value.length(); index++) {
        appendByte((byte) value.charAt(index));
      }
    }

    private void appendHex(byte[] value) {
      final byte[] digits = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
      for (byte element : value) {
        appendByte(digits[(element >>> 4) & 0x0f]);
        appendByte(digits[element & 0x0f]);
      }
      Arrays.fill(digits, (byte) 0);
    }

    private void append(byte[] value, int offset, int length) {
      if (length < 0 || position > bytes.length - length) {
        throw new IllegalArgumentException("security manifest too large");
      }
      System.arraycopy(value, offset, bytes, position, length);
      position += length;
    }

    private void appendByte(byte value) {
      if (position == bytes.length) throw new IllegalArgumentException("security manifest too large");
      bytes[position++] = value;
    }
  }
}
