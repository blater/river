package io.riverdb.server.app;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

final class RiverDaemonRecordEnvelopeTest {
  @Test
  void identityRecordsAcceptPaddingAndEmbeddedChecksumMarker() {
    byte[] encoded = padded(RiverDaemonRecordEnvelope.record(List.of(
        "format=" + RiverDaemonIdentityRecords.BOOTSTRAP_FORMAT,
        "database-incarnation-high=1",
        "database-incarnation-low=2",
        "pid=3",
        "process-start-epoch-millis=4",
        "attempt-nonce=" + "a".repeat(32),
        "database-name=" + RiverDaemonIdentity.DATABASE_NAME,
        "security-name=" + RiverDaemonIdentity.SECURITY_NAME,
        "staging-name=record-sha256=embedded",
        "instance-stage-name=stage")));

    assertNotNull(RiverDaemonIdentityRecords.parseBootstrap(encoded));
  }

  @Test
  void runtimeRecordsRejectPaddingAndEmbeddedChecksumMarker() {
    byte[] valid = runtimeRecord("client-config=client");
    assertNotNull(RiverDaemonRuntimeCodec.parseRuntime(valid));

    byte[] padded = padded(valid);
    assertNull(RiverDaemonRuntimeCodec.parseRuntime(padded));

    byte[] embedded = runtimeRecord("client-config=record-sha256=embedded");
    assertNull(RiverDaemonRuntimeCodec.parseRuntime(embedded));
  }

  private static byte[] runtimeRecord(String clientConfig) {
    return RiverDaemonRecordEnvelope.record(List.of(
        "format=" + RiverDaemonRuntimeCodec.RUNTIME_FORMAT,
        "datadir=/tmp/river-data",
        "database-incarnation-high=1",
        "database-incarnation-low=2",
        "pid=3",
        "process-start-epoch-millis=4",
        "listen-address=127.0.0.1",
        "listen-port=1234",
        clientConfig,
        "credential-generation=1",
        "ready-file=",
        "owner-nonce=" + "a".repeat(32))).getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] padded(String record) {
    return padded(record.getBytes(StandardCharsets.UTF_8));
  }

  private static byte[] padded(byte[] bytes) {
    return Arrays.copyOf(bytes, bytes.length + 8);
  }
}
