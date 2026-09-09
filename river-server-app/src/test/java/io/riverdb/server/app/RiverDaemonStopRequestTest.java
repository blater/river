package io.riverdb.server.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

final class RiverDaemonStopRequestTest {
  private static final String OWNER_NONCE = "0123456789abcdef0123456789abcdef";
  private static final String REQUEST_NONCE = "fedcba9876543210fedcba9876543210";
  private static final String RUNTIME_CHECKSUM = "a".repeat(64);

  @Test
  void encodedRequestRoundTripsAllOwnerAndRuntimeFields() {
    byte[] encoded = RiverDaemonStopRequest.encode(
        17, 29, OWNER_NONCE, REQUEST_NONCE, RUNTIME_CHECKSUM, 123456789L)
        .getBytes(StandardCharsets.UTF_8);

    RiverDaemonStopRequest.Record record = RiverDaemonStopRequest.parse(encoded);

    assertNotNull(record);
    assertEquals(17, record.high);
    assertEquals(29, record.low);
    assertEquals(OWNER_NONCE, record.ownerNonce);
    assertEquals(REQUEST_NONCE, record.requestNonce);
    assertEquals(RUNTIME_CHECKSUM, record.runtimeChecksum);
    assertEquals(123456789L, record.requestedAt);
  }

  @Test
  void checksumTamperingRejectsEncodedRequest() {
    String encoded = RiverDaemonStopRequest.encode(
        17, 29, OWNER_NONCE, REQUEST_NONCE, RUNTIME_CHECKSUM, 123456789L);
    String tampered = encoded.replace(
        "requested-at-epoch-millis=123456789", "requested-at-epoch-millis=123456790");

    assertNull(RiverDaemonStopRequest.parse(tampered.getBytes(StandardCharsets.UTF_8)));
  }
}
