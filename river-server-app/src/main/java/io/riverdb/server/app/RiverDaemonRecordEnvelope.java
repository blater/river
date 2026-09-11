package io.riverdb.server.app;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/** Encodes and validates River's bounded UTF-8, checksummed record envelope. */
final class RiverDaemonRecordEnvelope {
  static final int MAX_RECORD_BYTES = 4096;

  private RiverDaemonRecordEnvelope() { }

  static String record(List<String> fields) {
    StringBuilder body = new StringBuilder(MAX_RECORD_BYTES);
    for (String field : fields) body.append(field).append('\n');
    byte[] digest = digest(body.toString().getBytes(StandardCharsets.UTF_8));
    body.append("record-sha256=").append(HexFormat.of().formatHex(digest)).append('\n');
    java.util.Arrays.fill(digest, (byte) 0);
    return body.toString();
  }

  static Envelope decode(byte[] bytes, int fieldCount, String format) {
    if (bytes == null) return null;
    int length = bytes.length;
    for (int index = 0; index < bytes.length; index++) {
      if (bytes[index] == 0) {
        for (int tail = index; tail < bytes.length; tail++) {
          if (bytes[tail] != 0) return null;
        }
        length = index;
        break;
      }
    }
    String text;
    try {
      text = StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes, 0, length)).toString();
    } catch (CharacterCodingException failure) {
      return null;
    }
    int checksumStart = text.indexOf("\nrecord-sha256=");
    if (checksumStart < 0) return null;
    int end = checksumStart + 1;
    String prefix = text.substring(0, end);
    if (!prefix.endsWith("\n")) return null;
    String[] fields = prefix.substring(0, prefix.length() - 1).split("\\n", -1);
    String checksumLine = text.substring(end + "record-sha256=".length());
    if (fields.length != fieldCount || !checksumLine.endsWith("\n")) return null;
    String checksum = checksumLine.substring(0, checksumLine.length() - 1);
    if (!checksum.matches("[0-9a-f]{64}")) return null;
    byte[] expected = digest(prefix.getBytes(StandardCharsets.UTF_8));
    boolean valid = checksum.equals(HexFormat.of().formatHex(expected));
    java.util.Arrays.fill(expected, (byte) 0);
    return valid && format.equals(value(fields[0], "format="))
        ? new Envelope(fields, checksum) : null;
  }

  private static String value(String field, String key) {
    return field.startsWith(key) ? field.substring(key.length()) : "";
  }

  private static byte[] digest(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (Exception failure) {
      throw new IllegalStateException(failure);
    }
  }

  static final class Envelope {
    final String[] fields;
    final String checksum;

    Envelope(String[] fields, String checksum) {
      this.fields = fields;
      this.checksum = checksum;
    }
  }
}
