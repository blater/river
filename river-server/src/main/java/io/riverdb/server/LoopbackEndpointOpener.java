package io.riverdb.server;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.RiverDatabase;
import io.riverdb.protocol.auth.TlsChannelBinding;
import io.riverdb.protocol.auth.TokenAuthenticator;
import java.io.IOException;
import java.net.Socket;
import java.security.SecureRandom;
import javax.net.ssl.SSLSocket;

/** Performs the transport and authentication setup for one server connection. */
final class LoopbackEndpointOpener {
  private LoopbackEndpointOpener() { }

  static void open(
      Socket connection,
      RiverDatabase database,
      TokenAuthenticator authenticator,
      SecureRandom random,
      SecurityAuditLog audit,
      int authenticationTimeoutMillis,
      LoopbackEndpointOpenResult result) throws IOException {
    result.reset();
    if (authenticator == null) {
      result.set(new SessionEndpoint(database), 0);
      return;
    }
    if (!(connection instanceof SSLSocket secure)) {
      result.fail(StatusCode.INVARIANT_BROKEN);
      return;
    }
    secure.setEnabledProtocols(new String[] {"TLSv1.3"});
    secure.startHandshake();
    byte[] binding = new byte[TlsChannelBinding.BINDING_BYTES];
    StatusCode bindingStatus = TlsChannelBinding.export(secure.getSession(), binding);
    if (!bindingStatus.isOk()) {
      result.fail(bindingStatus);
      return;
    }
    long challengeHigh;
    long challengeLow;
    do {
      challengeHigh = random.nextLong();
      challengeLow = random.nextLong();
    } while (challengeHigh == 0 && challengeLow == 0);
    result.set(
        new SessionEndpoint(
            database,
            authenticator,
            challengeHigh,
            challengeLow,
            binding,
            audit),
        System.nanoTime() + authenticationTimeoutMillis * 1_000_000L);
  }
}
