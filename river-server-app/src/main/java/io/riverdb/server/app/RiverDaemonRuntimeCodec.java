package io.riverdb.server.app;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

final class RiverDaemonRuntimeCodec {
  static final String RUNTIME_FORMAT = "riverd-runtime-v2";
  static final String READY_FORMAT = "riverd-ready-v2";

  private RiverDaemonRuntimeCodec() {
  }

  static String runtimeBody(RiverDaemonRuntimeRecords.Metadata metadata) {
    return RiverDaemonIdentityRecords.record(List.of(
        "format=" + RUNTIME_FORMAT,
        "datadir=" + metadata.datadir,
        "database-incarnation-high=" + metadata.incarnation.high(),
        "database-incarnation-low=" + metadata.incarnation.low(),
        "pid=" + metadata.owner.pid,
        "process-start-epoch-millis=" + metadata.owner.start,
        "listen-address=" + metadata.listenAddress,
        "listen-port=" + metadata.listenPort,
        "client-config=" + metadata.clientConfig,
        "credential-generation=" + metadata.credentialGeneration,
        "ready-file=" + metadata.readyFile,
        "owner-nonce=" + metadata.owner.nonce));
  }

  static String readyBody(RiverDaemonRuntimeRecords.Metadata metadata, String certificate) {
    Path datadir = Path.of(metadata.datadir);
    return RiverDaemonIdentityRecords.record(List.of(
        "format=" + READY_FORMAT,
        "datadir=" + metadata.datadir,
        "database-incarnation-high=" + metadata.incarnation.high(),
        "database-incarnation-low=" + metadata.incarnation.low(),
        "data=" + datadir.resolve(RiverDaemonIdentity.DATABASE_NAME),
        "identity=" + datadir.resolve(RiverDaemonIdentity.INSTANCE_FILE),
        "runtime-file=" + RiverDaemonRuntimeStorage.runtimePath(
            metadata.runtimeRoot, metadata.datadir),
        "listen-address=" + metadata.listenAddress,
        "listen-port=" + metadata.listenPort,
        "pid=" + metadata.owner.pid,
        "protocol=" + protocolTag(),
        "transport=tls-v1.3",
        "client-config=" + metadata.clientConfig,
        "server-certificate-sha256=" + certificate,
        "owner-nonce=" + metadata.owner.nonce,
        "status=ready"));
  }

  static String protocolTag() {
    return "river-v" + io.riverdb.protocol.ProtocolFrameCodec.VERSION;
  }

  static RiverDaemonRuntimeModel.RuntimeRecord parseRuntime(byte[] bytes) {
    Envelope envelope = envelope(bytes, 12, RUNTIME_FORMAT);
    if (envelope == null) return null;
    String[] fields = envelope.fields;
    try {
      return new RiverDaemonRuntimeModel.RuntimeRecord(
          value(fields[1], "datadir="),
          canonicalLong(value(fields[2], "database-incarnation-high=")),
          canonicalLong(value(fields[3], "database-incarnation-low=")),
          canonicalLong(value(fields[4], "pid=")),
          canonicalLong(value(fields[5], "process-start-epoch-millis=")),
          value(fields[6], "listen-address="),
          canonicalPort(value(fields[7], "listen-port=")),
          value(fields[8], "client-config="),
          canonicalLong(value(fields[9], "credential-generation=")),
          value(fields[10], "ready-file="), value(fields[11], "owner-nonce="),
          envelope.checksum);
    } catch (RuntimeException failure) {
      return null;
    }
  }

  static RiverDaemonRuntimeModel.ReadyRecord parseReady(byte[] bytes) {
    Envelope envelope = envelope(bytes, 16, READY_FORMAT);
    if (envelope == null) return null;
    String[] fields = envelope.fields;
    try {
      return new RiverDaemonRuntimeModel.ReadyRecord(value(fields[1], "datadir="),
          canonicalLong(value(fields[2], "database-incarnation-high=")),
          canonicalLong(value(fields[3], "database-incarnation-low=")),
          value(fields[4], "data="), value(fields[5], "identity="),
          value(fields[6], "runtime-file="), value(fields[7], "listen-address="),
          canonicalPort(value(fields[8], "listen-port=")),
          canonicalLong(value(fields[9], "pid=")), value(fields[10], "protocol="),
          value(fields[11], "transport="), value(fields[12], "client-config="),
          value(fields[13], "server-certificate-sha256="),
          value(fields[14], "owner-nonce="), value(fields[15], "status="));
    } catch (RuntimeException failure) {
      return null;
    }
  }

  private static Envelope envelope(byte[] bytes, int fieldCount, String format) {
    if (bytes == null) return null;
    String text;
    try {
      text = StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes)).toString();
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
    Arrays.fill(expected, (byte) 0);
    return valid && format.equals(value(fields[0], "format="))
        ? new Envelope(fields, checksum) : null;
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

  private static int canonicalPort(String value) {
    long parsed = canonicalLong(value);
    if (parsed < 1 || parsed > 65535) throw new NumberFormatException();
    return (int) parsed;
  }

  private static byte[] digest(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (Exception failure) {
      throw new IllegalStateException(failure);
    }
  }

  private static final class Envelope {
    final String[] fields;
    final String checksum;

    Envelope(String[] fields, String checksum) {
      this.fields = fields;
      this.checksum = checksum;
    }
  }
}
