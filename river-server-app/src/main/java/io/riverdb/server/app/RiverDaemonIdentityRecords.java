package io.riverdb.server.app;

import io.riverdb.base.id.DatabaseIncarnation;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/** Concrete codecs and validated values for the bounded riverd identity records. */
final class RiverDaemonIdentityRecords {
  static final int MAX_RECORD_BYTES = 4096;
  static final String INSTANCE_FORMAT = "riverd-instance-v1";
  static final String BOOTSTRAP_FORMAT = "riverd-bootstrap-v1";
  static final String LOCK_FORMAT = "riverd-lock-v1";

  private RiverDaemonIdentityRecords() {
  }

  static String record(List<String> fields) {
    StringBuilder body = new StringBuilder(MAX_RECORD_BYTES);
    for (String field : fields) body.append(field).append('\n');
    byte[] digest = digest(body.toString().getBytes(StandardCharsets.UTF_8));
    body.append("record-sha256=").append(HexFormat.of().formatHex(digest)).append('\n');
    java.util.Arrays.fill(digest, (byte) 0);
    return body.toString();
  }

  static InstanceRecord parseInstance(byte[] bytes) {
    String[] fields = envelope(bytes, 4, INSTANCE_FORMAT);
    if (fields == null || !"initial-wal-generation=1".equals(fields[3])) return null;
    try {
      long high = canonicalLong(value(fields[1], "database-incarnation-high="));
      long low = canonicalLong(value(fields[2], "database-incarnation-low="));
      return new InstanceRecord(DatabaseIncarnation.of(high, low), 1L);
    } catch (RuntimeException failure) {
      return null;
    }
  }

  static LockRecord parseLock(byte[] bytes) {
    String[] fields = envelope(bytes, 8, LOCK_FORMAT);
    if (fields == null) return null;
    try {
      long high = canonicalLong(value(fields[2], "database-incarnation-high="));
      long low = canonicalLong(value(fields[3], "database-incarnation-low="));
      long pid = canonicalLong(value(fields[4], "pid="));
      long start = canonicalLong(value(fields[5], "process-start-epoch-millis="));
      String datadir = value(fields[1], "datadir=");
      String command = value(fields[6], "command=");
      String nonce = value(fields[7], "owner-nonce=");
      if (!DatabaseIncarnation.of(high, low).isValid() || pid <= 0 || start < 0
          || !validDatadir(datadir) || !validCommand(command)
          || !nonce.matches("[0-9a-f]{32}")) return null;
      return new LockRecord(datadir, high, low, pid, start, command, nonce);
    } catch (RuntimeException failure) {
      return null;
    }
  }

  static BootstrapRecord parseBootstrap(byte[] bytes) {
    String[] fields = envelope(bytes, 12, BOOTSTRAP_FORMAT);
    if (fields == null) return null;
    try {
      long high = canonicalLong(value(fields[1], "database-incarnation-high="));
      long low = canonicalLong(value(fields[2], "database-incarnation-low="));
      long pid = canonicalLong(value(fields[3], "pid="));
      long start = canonicalLong(value(fields[4], "process-start-epoch-millis="));
      String command = value(fields[5], "command=");
      String nonce = value(fields[6], "attempt-nonce=");
      String database = value(fields[7], "database-name=");
      String security = value(fields[8], "security-name=");
      String audit = value(fields[9], "audit-name=");
      String staging = value(fields[10], "staging-name=");
      String instanceStage = value(fields[11], "instance-stage-name=");
      if (!DatabaseIncarnation.of(high, low).isValid() || pid <= 0 || start < 0
          || !validCommand(command) || !nonce.matches("[0-9a-f]{32}")
          || !RiverDaemonIdentity.DATABASE_NAME.equals(database)
          || !RiverDaemonIdentity.SECURITY_NAME.equals(security)
          || !RiverDaemonIdentity.AUDIT_NAME.equals(audit)) return null;
      return new BootstrapRecord(DatabaseIncarnation.of(high, low), pid, start, command, nonce,
          staging, instanceStage);
    } catch (RuntimeException failure) {
      return null;
    }
  }

  private static String[] envelope(byte[] bytes, int fieldCount, String format) {
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
    int end = text.indexOf("record-sha256=");
    if (end <= 0) return null;
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
    if (!valid || !format.equals(value(fields[0], "format="))) return null;
    return fields;
  }

  private static String value(String field, String key) {
    return field.startsWith(key) ? field.substring(key.length()) : "";
  }

  private static long canonicalLong(String value) {
    if (value == null || value.isEmpty()) throw new NumberFormatException();
    long parsed = Long.parseLong(value);
    if (!Long.toString(parsed).equals(value)) throw new NumberFormatException();
    return parsed;
  }

  static boolean validDatadir(String value) {
    if (!validAbsoluteNormalizedPath(value)) return false;
    try {
      Path path = Path.of(value);
      return path.getFileName() != null && path.getFileName().toString().indexOf('=') < 0;
    } catch (RuntimeException failure) {
      return false;
    }
  }

  static boolean validCommand(String command) {
    return command != null && !command.isBlank() && command.equals(command.trim())
        && validAbsoluteNormalizedPath(command);
  }

  private static boolean validAbsoluteNormalizedPath(String value) {
    if (value == null || value.isEmpty()) return false;
    for (int index = 0; index < value.length(); index++) {
      if (Character.isISOControl(value.charAt(index))) return false;
    }
    try {
      Path path = Path.of(value);
      return path.isAbsolute() && path.normalize().toString().equals(value);
    } catch (RuntimeException failure) {
      return false;
    }
  }

  private static byte[] digest(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (Exception failure) {
      throw new IllegalStateException(failure);
    }
  }

  static final class InstanceRecord {
    final DatabaseIncarnation incarnation;
    final long generation;

    InstanceRecord(DatabaseIncarnation incarnation, long generation) {
      this.incarnation = incarnation;
      this.generation = generation;
    }
  }

  static final class LockRecord {
    final String datadir;
    final long high;
    final long low;
    final long pid;
    final long start;
    final String command;
    final String nonce;

    LockRecord(String datadir, long high, long low, long pid, long start, String command,
        String nonce) {
      this.datadir = datadir;
      this.high = high;
      this.low = low;
      this.pid = pid;
      this.start = start;
      this.command = command;
      this.nonce = nonce;
    }
  }

  static final class BootstrapRecord {
    final DatabaseIncarnation incarnation;
    final long high;
    final long low;
    final long pid;
    final long start;
    final String command;
    final String nonce;
    final String stagingName;
    final String instanceStageName;

    BootstrapRecord(DatabaseIncarnation incarnation, long pid, long start, String command,
        String nonce, String stagingName, String instanceStageName) {
      this.incarnation = incarnation;
      this.high = incarnation.high();
      this.low = incarnation.low();
      this.pid = pid;
      this.start = start;
      this.command = command;
      this.nonce = nonce;
      this.stagingName = stagingName;
      this.instanceStageName = instanceStageName;
    }
  }
}
