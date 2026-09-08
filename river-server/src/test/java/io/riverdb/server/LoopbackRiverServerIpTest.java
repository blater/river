package io.riverdb.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.riverdb.base.error.StatusCode;
import io.riverdb.server.CredentialValidityFence;
import io.riverdb.server.CredentialValidityFenceOpenResult;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.EmbeddedRiver;
import io.riverdb.engine.api.DatabaseOpenResult;
import io.riverdb.engine.api.RiverDatabase;
import io.riverdb.engine.runtime.DatabaseResourcePlanRequest;
import io.riverdb.protocol.auth.TokenAuthenticator;
import io.riverdb.protocol.auth.TokenAuthenticatorOpenResult;
import io.riverdb.testsupport.SecurityAuditTestOwner;
import io.riverdb.testsupport.TestTlsContexts;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LoopbackRiverServerIpTest {
  private static final DatabaseIncarnation DATABASE = DatabaseIncarnation.of(71, 73);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void bindsAuthenticatedServerToIpv4Loopback(@TempDir Path root) throws Exception {
    InetAddress address = InetAddress.getByName("127.0.0.1");
    Fixture fixture = openFixture(root);
    try {
      LoopbackServerOpenResult opened = new LoopbackServerOpenResult();
      StatusCode status = LoopbackRiverServer.startAuthenticated(
          fixture.database, address, 0, TestTlsContexts.server(), fixture.authenticator,
          fixture.audit, validityFence(), LoopbackServerLimits.defaults(2), opened);
      assertEquals(StatusCode.OK, status);
      LoopbackRiverServer server = opened.server();
      fixture.server = server;
      assertEquals(address, server.listener.getInetAddress());
    } finally {
      assertEquals(StatusCode.OK, fixture.close());
    }
  }

  @Test
  void bindsAuthenticatedServerToIpv6Loopback(@TempDir Path root) throws Exception {
    InetAddress address = InetAddress.getByName("::1");
    Fixture fixture = openFixture(root);
    try {
      LoopbackServerOpenResult opened = new LoopbackServerOpenResult();
      StatusCode status = LoopbackRiverServer.startAuthenticated(
          fixture.database, address, 0, TestTlsContexts.server(), fixture.authenticator,
          fixture.audit, validityFence(), LoopbackServerLimits.defaults(2), opened);
      assertEquals(StatusCode.OK, status);
      LoopbackRiverServer server = opened.server();
      fixture.server = server;
      assertEquals(address, server.listener.getInetAddress());
    } finally {
      assertEquals(StatusCode.OK, fixture.close());
    }
  }

  @Test
  void rejectsNonLoopbackAuthenticatedBindBeforeSocketCreation(@TempDir Path root)
      throws Exception {
    Fixture fixture = openFixture(root);
    CredentialValidityFence fence = validityFence();
    try {
      LoopbackServerOpenResult opened = new LoopbackServerOpenResult();
      assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, LoopbackRiverServer.startAuthenticated(
          fixture.database, InetAddress.getByName("192.0.2.1"), 0,
          TestTlsContexts.server(), fixture.authenticator, fixture.audit,
          fence, LoopbackServerLimits.defaults(2), opened));
      assertNull(opened.server());
    } finally {
      assertEquals(StatusCode.OK, fence.close());
      assertEquals(StatusCode.OK, fixture.close());
    }
  }

  private static CredentialValidityFence validityFence() {
    CredentialValidityFenceOpenResult opened = new CredentialValidityFenceOpenResult();
    long now = System.currentTimeMillis();
    if (CredentialValidityFence.create(now - 300_000L, now + 86_400_000L, opened)
        != StatusCode.OK) {
      throw new AssertionError("test credential validity bounds");
    }
    return opened.fence();
  }

  private static Fixture openFixture(Path root) throws Exception {
    DatabaseOpenResult databaseResult = new DatabaseOpenResult();
    assertEquals(StatusCode.OK, EmbeddedRiver.create(databaseRequest(), root, DATABASE, GENERATION, 2,
        databaseResult));
    TokenAuthenticatorOpenResult authenticatorResult = new TokenAuthenticatorOpenResult();
    byte[] token = "loopback-ip-test-token".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    assertEquals(StatusCode.OK, TokenAuthenticator.create(
        token, token.length, authenticatorResult));
    Arrays.fill(token, (byte) 0);
    return new Fixture(databaseResult.database(), authenticatorResult.authenticator(),
        SecurityAuditTestOwner.create(root, DATABASE, 1, 64L * 1024L * 1024L, 256L * 1024L));
  }

  private static DatabaseResourcePlanRequest databaseRequest() {
    return new DatabaseResourcePlanRequest()
        .memory(256_000_000L, 0, 0, 0, 64_000_000L)
        .lockProviderBytes(8_000_000L)
        .versionWorkspaceBytes(8_000_000L)
        .indexedPageCache(32_000_000L, 8_000_000L)
        .capacity(4, Integer.MAX_VALUE, 800, 64_000_000L)
        .maximumDelivery(Integer.MAX_VALUE, 800, 64_000_000L);
  }

  private static final class Fixture {
    private final RiverDatabase database;
    private final TokenAuthenticator authenticator;
    private final SecurityAuditLog audit;
    private LoopbackRiverServer server;

    Fixture(RiverDatabase database, TokenAuthenticator authenticator, SecurityAuditLog audit) {
      this.database = database;
      this.authenticator = authenticator;
      this.audit = audit;
    }

    StatusCode close() {
      StatusCode status = server == null ? audit.finishClose() : server.close();
      StatusCode databaseStatus = database.close();
      if (status.isOk() && databaseStatus != StatusCode.CLOSED) status = databaseStatus;
      StatusCode authenticatorStatus = authenticator.destroy();
      if (status.isOk() && authenticatorStatus != StatusCode.CLOSED) status = authenticatorStatus;
      return status;
    }
  }
}
