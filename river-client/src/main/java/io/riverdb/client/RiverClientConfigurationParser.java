package io.riverdb.client;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.platform.riverd.RiverDaemonFileSystem;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Validates the ordered, checksummed record emitted for one client configuration. */
final class RiverClientConfigurationParser {
  private static final String[] KEYS = {
      "format", "database-incarnation-high", "database-incarnation-low",
      "credential-generation", "principal-id", "transport", "protocol", "host", "port",
      "server-certificate-file", "server-certificate-sha256", "token-file", "record-sha256"
  };

  private RiverClientConfigurationParser() { }

  static StatusCode parse(
      RiverDaemonFileSystem fileSystem,
      byte[] bytes,
      RiverClientConfigurationResult result) {
    try {
      String text = decode(bytes);
      String[] lines = text.split("\\n", -1);
      if (lines.length != KEYS.length + 1 || !lines[lines.length - 1].isEmpty()) {
        return StatusCode.CORRUPTION;
      }
      String[] values = new String[KEYS.length];
      for (int index = 0; index < KEYS.length; index++) {
        String line = lines[index];
        int equals = line.indexOf('=');
        if (equals <= 0 || line.indexOf('=', equals + 1) >= 0
            || !KEYS[index].equals(line.substring(0, equals))) {
          return StatusCode.CORRUPTION;
        }
        values[index] = line.substring(equals + 1);
        if (!validValue(values[index])) return StatusCode.CORRUPTION;
      }
      if (!lowerHex(values[12], 64)) return StatusCode.CORRUPTION;
      int checksumLineStart = text.lastIndexOf("record-sha256=");
      if (checksumLineStart <= 0 || text.charAt(checksumLineStart - 1) != '\n') {
        return StatusCode.CORRUPTION;
      }
      byte[] checksumPrefix = text.substring(0, checksumLineStart)
          .getBytes(StandardCharsets.UTF_8);
      byte[] computed = MessageDigest.getInstance("SHA-256").digest(checksumPrefix);
      if (!MessageDigest.isEqual(computed, HexFormat.of().parseHex(values[12]))) {
        return StatusCode.CORRUPTION;
      }
      if (!"riverd-client-v1".equals(values[0]) || !"1".equals(values[4])
          || !"tls-v1.3".equals(values[5]) || !"river-v5".equals(values[6])) {
        return StatusCode.CORRUPTION;
      }
      long high = canonicalLong(values[1]);
      long low = canonicalLong(values[2]);
      DatabaseIncarnation incarnation = DatabaseIncarnation.of(high, low);
      long generation = canonicalPositiveLong(values[3]);
      if (!validHost(values[7])) return StatusCode.CORRUPTION;
      long portValue = canonicalPositiveLong(values[8]);
      if (portValue > 65535) return StatusCode.CORRUPTION;
      Path certificate = absoluteNormalized(values[9]);
      Path token = absoluteNormalized(values[11]);
      if (certificate == null || token == null || !lowerHex(values[10], 64)) {
        return StatusCode.CORRUPTION;
      }
      result.complete(new RiverClientConfiguration(
          fileSystem, incarnation, generation, values[7], (int) portValue, certificate,
          HexFormat.of().parseHex(values[10]), token));
      return StatusCode.OK;
    } catch (CharacterCodingException failure) {
      return StatusCode.CORRUPTION;
    } catch (GeneralSecurityException failure) {
      return StatusCode.INVARIANT_BROKEN;
    } catch (IllegalArgumentException failure) {
      return StatusCode.CORRUPTION;
    }
  }

  private static String decode(byte[] bytes) throws CharacterCodingException {
    return StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString();
  }

  private static long canonicalLong(String value) {
    long parsed = Long.parseLong(value);
    if (!Long.toString(parsed).equals(value)) throw new IllegalArgumentException("noncanonical");
    return parsed;
  }

  private static long canonicalPositiveLong(String value) {
    long parsed = canonicalLong(value);
    if (parsed <= 0) throw new IllegalArgumentException("nonpositive");
    return parsed;
  }

  private static Path absoluteNormalized(String value) {
    Path path = Path.of(value);
    return RiverClientFileReader.validAbsolutePath(path) ? path : null;
  }

  private static boolean validValue(String value) {
    if (value.isEmpty() || !value.equals(value.strip())) return false;
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (character == '=' || character == '\r' || character == '\n' || character == 0
          || Character.getType(character) == Character.CONTROL) return false;
    }
    return true;
  }

  private static boolean validHost(String value) {
    return "localhost".equals(value) || "127.0.0.1".equals(value) || "::1".equals(value);
  }

  private static boolean lowerHex(String value, int length) {
    if (value.length() != length) return false;
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (!(character >= '0' && character <= '9')
          && !(character >= 'a' && character <= 'f')) return false;
    }
    return true;
  }
}
