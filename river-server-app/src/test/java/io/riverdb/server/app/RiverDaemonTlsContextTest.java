package io.riverdb.server.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import java.security.SecureRandom;
import java.time.Instant;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.Test;

final class RiverDaemonTlsContextTest {
  @Test
  void buildsTls13ContextAndReleasesSessionContext() {
    RiverDaemonCredentials.CredentialResult credentials =
        new RiverDaemonCredentials.CredentialResult();
    SecureRandom random = new SecureRandom();
    assertEquals(StatusCode.OK, RiverDaemonCredentials.generate(
        DatabaseIncarnation.of(41, 43), 1, random, Instant.now().minusSeconds(30), credentials));

    RiverDaemonCredentials.Material material = credentials.material();
    RiverDaemonTlsContext.TlsContextResult tls = new RiverDaemonTlsContext.TlsContextResult();
    assertEquals(StatusCode.OK, RiverDaemonTlsContext.create(material, random, tls));
    SSLContext context = tls.context();
    assertNotNull(context);
    assertEquals("TLSv1.3", context.getProtocol());

    assertEquals(StatusCode.OK, RiverDaemonTlsContext.cleanup(tls));
    assertNull(tls.context());
    assertEquals(StatusCode.OK, material.destroy());
    assertEquals(StatusCode.OK, RiverDaemonTlsContext.cleanup(tls));
  }

  @Test
  void rejectsMissingInputsWithoutCreatingContext() {
    RiverDaemonTlsContext.TlsContextResult result = new RiverDaemonTlsContext.TlsContextResult();
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        RiverDaemonTlsContext.create(null, new SecureRandom(), result));
    assertNull(result.context());
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        RiverDaemonTlsContext.create(null, new SecureRandom(), null));
  }
}
